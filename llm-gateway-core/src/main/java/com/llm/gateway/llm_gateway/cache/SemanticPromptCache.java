package com.llm.gateway.llm_gateway.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Embedding-similarity index over the exact-match prompt cache.
 *
 * <p>The SHA-256 cache in {@link PromptCacheService} only hits on byte-identical prompts —
 * "summarize this report" and "please summarize this report" miss. This component keeps a small
 * per-{@code provider:model} index of prompt embeddings in Redis; on an exact-cache miss the
 * incoming prompt is embedded and compared (cosine similarity) against the index, and a
 * sufficiently similar previous prompt re-uses its cached response.
 *
 * <h3>Storage layout</h3>
 *
 * <ul>
 *   <li>{@code llm:cache:sem:{provider}:{model}:vec} — Redis hash: exact-cache key → JSON float[]
 *   <li>{@code llm:cache:sem:{provider}:{model}:lru} — Redis zset: exact-cache key → insert millis,
 *       used to evict the oldest entries once the index exceeds {@code max-entries}
 * </ul>
 *
 * <p>Disabled by default ({@code llm.cache.semantic.enabled=false}) because every exact-cache miss
 * then costs one embedding call. The index is linear-scanned in memory, so {@code max-entries}
 * bounds both Redis memory and lookup cost. Errors are always swallowed — semantic caching is a
 * best-effort optimisation and must never break a request.
 */
@Slf4j
@Component
public class SemanticPromptCache {

  private final StringRedisTemplate redisTemplate;
  private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
  private final ObjectMapper objectMapper;

  @Value("${llm.cache.semantic.enabled:false}")
  private boolean enabled;

  @Value("${llm.cache.semantic.similarity-threshold:0.95}")
  private double similarityThreshold;

  @Value("${llm.cache.semantic.max-entries:256}")
  private long maxEntries;

  @Value("${llm.cache.semantic.index-ttl-minutes:120}")
  private long indexTtlMinutes;

  @Value("${llm.cache.key-prefix:llm:cache}")
  private String keyPrefix;

  public SemanticPromptCache(
      StringRedisTemplate redisTemplate,
      ObjectProvider<EmbeddingModel> embeddingModelProvider,
      ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.embeddingModelProvider = embeddingModelProvider;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns the exact-cache key of the most similar previously seen prompt, if its cosine
   * similarity clears the configured threshold.
   */
  public Optional<String> findSimilarKey(String provider, String model, String prompt) {
    if (!enabled) return Optional.empty();
    try {
      float[] queryVec = embed(prompt);
      if (queryVec == null) return Optional.empty();

      Map<Object, Object> index = redisTemplate.opsForHash().entries(vecKey(provider, model));
      String bestKey = null;
      double bestScore = similarityThreshold;
      for (Map.Entry<Object, Object> entry : index.entrySet()) {
        float[] candidate = objectMapper.readValue((String) entry.getValue(), float[].class);
        double score = cosine(queryVec, candidate);
        if (score >= bestScore) {
          bestScore = score;
          bestKey = (String) entry.getKey();
        }
      }
      if (bestKey != null) {
        log.info(
            "CACHE | SEMANTIC HIT | provider={} | model={} | similarity={}",
            provider, model, String.format("%.4f", bestScore));
        return Optional.of(bestKey);
      }
      return Optional.empty();
    } catch (Exception e) {
      log.warn("CACHE | Semantic lookup error (bypassed) | error={}", e.getMessage());
      return Optional.empty();
    }
  }

  /** Registers a prompt's embedding under its exact-cache key, evicting the oldest when full. */
  public void register(String provider, String model, String prompt, String exactCacheKey) {
    if (!enabled) return;
    try {
      float[] vec = embed(prompt);
      if (vec == null) return;

      String vecKey = vecKey(provider, model);
      String lruKey = lruKey(provider, model);
      redisTemplate.opsForHash().put(vecKey, exactCacheKey, objectMapper.writeValueAsString(vec));
      redisTemplate.opsForZSet().add(lruKey, exactCacheKey, System.currentTimeMillis());

      Long size = redisTemplate.opsForZSet().zCard(lruKey);
      if (size != null && size > maxEntries) {
        var oldest = redisTemplate.opsForZSet().range(lruKey, 0, size - maxEntries - 1);
        if (oldest != null && !oldest.isEmpty()) {
          redisTemplate.opsForZSet().remove(lruKey, oldest.toArray());
          redisTemplate.opsForHash().delete(vecKey, oldest.toArray());
        }
      }
      Duration ttl = Duration.ofMinutes(indexTtlMinutes);
      redisTemplate.expire(vecKey, ttl);
      redisTemplate.expire(lruKey, ttl);
    } catch (Exception e) {
      log.warn("CACHE | Semantic register error (non-fatal) | error={}", e.getMessage());
    }
  }

  /** Drops an index entry whose underlying exact-cache value has expired. */
  public void removeStale(String provider, String model, String exactCacheKey) {
    try {
      redisTemplate.opsForHash().delete(vecKey(provider, model), exactCacheKey);
      redisTemplate.opsForZSet().remove(lruKey(provider, model), exactCacheKey);
    } catch (Exception e) {
      log.debug("CACHE | Semantic stale-cleanup error | error={}", e.getMessage());
    }
  }

  private float[] embed(String text) {
    EmbeddingModel model = embeddingModelProvider.getIfAvailable();
    if (model == null) {
      log.debug("CACHE | Semantic cache enabled but no EmbeddingModel bean available");
      return null;
    }
    return model.embed(text);
  }

  static double cosine(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length || a.length == 0) return -1;
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    if (normA == 0 || normB == 0) return -1;
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  private String vecKey(String provider, String model) {
    return keyPrefix + ":sem:" + provider.toLowerCase() + ":" + model + ":vec";
  }

  private String lruKey(String provider, String model) {
    return keyPrefix + ":sem:" + provider.toLowerCase() + ":" + model + ":lru";
  }
}
