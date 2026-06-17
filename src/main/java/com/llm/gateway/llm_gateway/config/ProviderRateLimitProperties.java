package com.llm.gateway.llm_gateway.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-provider rate limit configuration.
 *
 * Bound from {@code gateway.provider-rate-limits.limits} in application.yaml.
 * Provides a simple in-memory sliding minute-window advisory check backed by
 * per-provider counters. If a provider has no entry in the limits map, calls
 * are always allowed through.
 *
 * <pre>
 * gateway:
 *   provider-rate-limits:
 *     limits:
 *       openai:
 *         requests-per-minute: 60
 *         tokens-per-minute: 90000
 *       anthropic:
 *         requests-per-minute: 40
 *         tokens-per-minute: 100000
 * </pre>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "gateway.provider-rate-limits")
public class ProviderRateLimitProperties {

    /**
     * Provider name (lower-case) -> per-provider limit settings.
     * Spring Boot populates this from {@code gateway.provider-rate-limits.limits.*}.
     */
    private Map<String, ProviderLimit> limits = new LinkedHashMap<>();

    // ── In-memory counters (reset each minute) ───────────────────────────────

    /** request counters: provider -> [epochMinute, requestCount] */
    private final ConcurrentHashMap<String, long[]> requestCounters = new ConcurrentHashMap<>();

    /** token counters: provider -> [epochMinute, tokenCount] */
    private final ConcurrentHashMap<String, long[]> tokenCounters = new ConcurrentHashMap<>();

    /**
     * Advisory request-rate check: increments the per-minute counter for the
     * provider and returns {@code true} if the limit has NOT been exceeded.
     * When no limit is configured for the provider, always returns {@code true}.
     */
    public boolean checkAndIncrementRequest(String provider) {
        ProviderLimit limit = limits.get(provider.toLowerCase());
        if (limit == null || limit.getRequestsPerMinute() <= 0) return true;

        long minute = currentMinute();
        long[] slot = requestCounters.computeIfAbsent(provider, k -> new long[]{minute, 0L});

        synchronized (slot) {
            if (slot[0] != minute) {
                slot[0] = minute;
                slot[1] = 0L;
            }
            slot[1]++;
            boolean withinLimit = slot[1] <= limit.getRequestsPerMinute();
            if (!withinLimit) {
                log.warn("PROVIDER_RATE_LIMIT | requests/min exceeded | provider={} | count={} | limit={}",
                        provider, slot[1], limit.getRequestsPerMinute());
            }
            return withinLimit;
        }
    }

    /**
     * Advisory token-rate check: adds {@code tokenCount} to the per-minute token
     * counter and returns {@code true} if the limit has NOT been exceeded.
     * When no limit is configured for the provider, always returns {@code true}.
     */
    public boolean checkAndIncrementTokens(String provider, int tokenCount) {
        ProviderLimit limit = limits.get(provider.toLowerCase());
        if (limit == null || limit.getTokensPerMinute() <= 0) return true;

        long minute = currentMinute();
        long[] slot = tokenCounters.computeIfAbsent(provider, k -> new long[]{minute, 0L});

        synchronized (slot) {
            if (slot[0] != minute) {
                slot[0] = minute;
                slot[1] = 0L;
            }
            slot[1] += tokenCount;
            boolean withinLimit = slot[1] <= limit.getTokensPerMinute();
            if (!withinLimit) {
                log.warn("PROVIDER_RATE_LIMIT | tokens/min exceeded | provider={} | tokens={} | limit={}",
                        provider, slot[1], limit.getTokensPerMinute());
            }
            return withinLimit;
        }
    }

    private static long currentMinute() {
        return System.currentTimeMillis() / 60_000L;
    }

    @Data
    public static class ProviderLimit {
        /** Maximum requests allowed per minute for this provider. */
        private int requestsPerMinute;
        /** Maximum tokens (prompt + completion) allowed per minute for this provider. */
        private int tokensPerMinute;
    }
}
