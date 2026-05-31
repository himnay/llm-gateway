package com.llm.gateway.llm_gateway.cache;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Redis-backed prompt response cache.
 *
 * <h3>Cache key strategy</h3>
 * {@code llm:cache:{provider}:{effectiveModel}:{SHA-256(sanitizedPrompt)}}
 *
 * <p>Using a hash of the prompt ensures the Redis key stays small regardless
 * of prompt length, while still being effectively unique per prompt content.</p>
 *
 * <p>The cache is disabled transparently when Redis is unavailable – a warning
 * is logged and the request is passed through normally.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

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
     * @param provider     canonical provider name (e.g. {@code "openai"})
     * @param request      the sanitized request (prompt + model)
     * @return an {@link Optional} wrapping the cached {@link LlmResponse}
     */
    public Optional<LlmResponse> get(String provider, LlmRequest request) {
        if (!enabled) return Optional.empty();

        try {
            String key   = buildKey(provider, request);
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.debug("CACHE | MISS | key={}", key);
                return Optional.empty();
            }
            LlmResponse response = objectMapper.readValue(value, LlmResponse.class);
            log.info("CACHE | HIT  | key={} | provider={}", key, provider);
            return Optional.of(response);
        } catch (Exception e) {
            log.warn("CACHE | Read error (cache bypassed) | provider={} | error={}", provider, e.getMessage());
            return Optional.empty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Write
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Stores the response in Redis.  Errors are swallowed so a Redis outage
     * never breaks a successful LLM call.
     *
     * @param provider  canonical provider name
     * @param request   the request used to compute the cache key
     * @param response  the response to cache
     */
    public void put(String provider, LlmRequest request, LlmResponse response) {
        if (!enabled) return;
        if (response.getError() != null) return; // never cache errors

        try {
            String key   = buildKey(provider, request);
            String value = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(ttlMinutes));
            log.debug("CACHE | STORE | key={} | ttl={}min", key, ttlMinutes);
        } catch (Exception e) {
            log.warn("CACHE | Write error (non-fatal) | provider={} | error={}", provider, e.getMessage());
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
    // Key construction
    // ──────────────────────────────────────────────────────────────────────────

    private String buildKey(String provider, LlmRequest request) {
        String model        = request.getModel() != null ? request.getModel() : "default";
        String promptHash   = sha256(request.getPrompt());
        return keyPrefix + ":" + provider.toLowerCase() + ":" + model + ":" + promptHash;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in JDK – this branch is unreachable
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

