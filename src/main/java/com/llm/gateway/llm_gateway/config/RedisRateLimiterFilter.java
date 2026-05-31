package com.llm.gateway.llm_gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;

/**
 * Sliding-window Redis rate limiter applied to all /api/llm/** requests.
 *
 * Uses a per-client-IP counter in Redis with a fixed window keyed by epoch minute.
 * Fails open on Redis errors so an unavailable Redis does not block LLM traffic.
 *
 * Disable in tests via: gateway.rate-limiter.enabled=false
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "gateway.rate-limiter.enabled", havingValue = "true", matchIfMissing = true)
public class RedisRateLimiterFilter implements WebFilter {

    private static final String KEY_PREFIX = "rl:llm:";

    private final ReactiveStringRedisTemplate redis;
    private final int maxRequests;
    private final long windowSeconds;

    public RedisRateLimiterFilter(
            ReactiveStringRedisTemplate redis,
            @Value("${gateway.rate-limiter.max-requests:60}") int maxRequests,
            @Value("${gateway.rate-limiter.window-seconds:60}") long windowSeconds) {
        this.redis = redis;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/llm")) {
            return chain.filter(exchange);
        }

        String clientIp = resolveClientIp(exchange);
        long windowBucket = Instant.now().getEpochSecond() / windowSeconds;
        String key = KEY_PREFIX + clientIp + ":" + windowBucket;

        return redis.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count == 1L) {
                        // Set TTL on first request in window — 2x window so key expires cleanly
                        return redis.expire(key, Duration.ofSeconds(windowSeconds * 2))
                                .thenReturn(count);
                    }
                    return Mono.just(count);
                })
                .flatMap(count -> {
                    exchange.getResponse().getHeaders().set("X-RateLimit-Limit",
                            String.valueOf(maxRequests));
                    exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                            String.valueOf(Math.max(0L, maxRequests - count)));

                    if (count > maxRequests) {
                        log.warn("RATE_LIMIT | Blocked | ip={} | count={}/{}", clientIp, count, maxRequests);
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(windowSeconds));
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(ex -> {
                    // Fail open — Redis unavailability must not block LLM traffic
                    log.error("RATE_LIMIT | Redis error, allowing request | {}", ex.getMessage());
                    return chain.filter(exchange);
                });
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }
}
