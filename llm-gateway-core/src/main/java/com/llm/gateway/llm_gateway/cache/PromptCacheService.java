package com.llm.gateway.llm_gateway.cache;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.exception.LlmGatewayInternalException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed prompt response cache.
 *
 * <h3>Cache key strategy</h3>
 *
 * {@code llm:cache:{provider}:{effectiveModel}:{SHA-256(sanitizedPrompt)}}
 *
 * <p>Using a hash of the prompt ensures the Redis key stays small regardless of prompt length,
 * while still being effectively unique per prompt content.
 *
 * <p>The cache is disabled transparently when Redis is unavailable – a warning is logged and the
 * request is passed through normally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptCacheService {

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final SemanticPromptCache semanticCache;

  @Value("${llm.cache.enabled:true}")
  private boolean enabled;

  @Value("${llm.cache.ttl-minutes:60}")
  private long ttlMinutes;

  @Value("${llm.cache.key-prefix:llm:cache}")
  private String keyPrefix;

  // ──────────────────────────────────────────────────────────────────────────
  // Read
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns the cached response if present and caching is enabled.
   *
   * @param provider canonical provider name (e.g. {@code "openai"})
   * @param request the sanitized request (prompt + model)
   * @return an {@link Optional} wrapping the cached {@link LlmResponse}
   */
  public Optional<LlmResponse> get(String provider, LlmRequest request) {
    if (!enabled) return Optional.empty();

    try {
      String key = buildKey(provider, request);
      String value = redisTemplate.opsForValue().get(key);
      if (value == null) {
        log.debug("CACHE | MISS | key={}", key);
        return semanticLookup(provider, request);
      }
      LlmResponse response = objectMapper.readValue(value, LlmResponse.class);
      log.info("CACHE | HIT  | key={} | provider={}", key, provider);
      return Optional.of(response);
    } catch (Exception e) {
      log.warn(
          "CACHE | Read error (cache bypassed) | provider={} | error={}", provider, e.getMessage());
      return Optional.empty();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Write
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Stores the response in Redis. Errors are swallowed so a Redis outage never breaks a successful
   * LLM call.
   *
   * @param provider canonical provider name
   * @param request the request used to compute the cache key
   * @param response the response to cache
   */
  public void put(String provider, LlmRequest request, LlmResponse response) {
    if (!enabled) return;
    if (response.getError() != null) return; // never cache errors

    try {
      String key = buildKey(provider, request);
      String value = objectMapper.writeValueAsString(response);
      redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(ttlMinutes));
      semanticCache.register(provider, effectiveModel(request), request.getPrompt(), key);
      log.debug("CACHE | STORE | key={} | ttl={}min", key, ttlMinutes);
    } catch (Exception e) {
      log.warn(
          "CACHE | Write error (non-fatal) | provider={} | error={}", provider, e.getMessage());
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Eviction helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Removes a single cache entry (e.g. after a model update). */
  public void evict(String provider, LlmRequest request) {
    try {
      redisTemplate.delete(buildKey(provider, request));
    } catch (Exception e) {
      log.warn("CACHE | Eviction error | error={}", e.getMessage());
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Semantic fallback
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Exact-key miss fallback: looks up a semantically similar previous prompt and serves its cached
   * response. A stale index entry (underlying value expired) is pruned and treated as a miss.
   */
  private Optional<LlmResponse> semanticLookup(String provider, LlmRequest request) {
    String model = effectiveModel(request);
    return semanticCache
        .findSimilarKey(provider, model, request.getPrompt())
        .flatMap(
            similarKey -> {
              String value = redisTemplate.opsForValue().get(similarKey);
              if (value == null) {
                semanticCache.removeStale(provider, model, similarKey);
                return Optional.empty();
              }
              try {
                return Optional.of(objectMapper.readValue(value, LlmResponse.class));
              } catch (Exception e) {
                log.warn("CACHE | Semantic hit deserialization error | error={}", e.getMessage());
                return Optional.empty();
              }
            });
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Key construction
  // ──────────────────────────────────────────────────────────────────────────

  private static String effectiveModel(LlmRequest request) {
    return request.getModel() != null ? request.getModel() : "default";
  }

  private String buildKey(String provider, LlmRequest request) {
    String model = effectiveModel(request);
    // Include every field that affects the model's response so that
    // different system prompts / template vars never collide on the same key.
    String keySource =
        request.getPrompt()
            + "|"
            + (request.getSystemPrompt() != null ? request.getSystemPrompt() : "")
            + "|"
            + (request.getAssistantMessage() != null ? request.getAssistantMessage() : "")
            + "|"
            + (request.getTemplateVars() != null ? request.getTemplateVars().toString() : "");
    return keyPrefix + ":" + provider.toLowerCase() + ":" + model + ":" + sha256(keySource);
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is always available in JDK – this branch is unreachable
      throw new LlmGatewayInternalException("SHA-256 unavailable", e);
    }
  }
}
