package com.llm.gateway.llm_gateway.config;

import jakarta.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
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

/**
 * Sliding-window Redis rate limiter applied to all /llm/** requests.
 *
 * <p>Uses a per-client-IP counter in Redis with a fixed window keyed by epoch bucket. Fails open on
 * Redis errors so an unavailable Redis does not block LLM traffic.
 *
 * <p>X-Forwarded-For is only honoured when the actual remote IP is in the configured
 * trusted-proxies list, preventing IP spoofing by untrusted clients.
 *
 * <p>Disable in tests via: gateway.rate-limiter.enabled=false
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Component
@ConditionalOnProperty(
    name = "gateway.rate-limiter.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RedisRateLimiterFilter implements WebFilter {

  private static final String KEY_PREFIX = "rl:llm:";

  private final ReactiveStringRedisTemplate redis;
  private final int maxRequests;
  private final long windowSeconds;
  private final String trustedProxiesRaw;
  private Set<String> trustedProxies;

  public RedisRateLimiterFilter(
      ReactiveStringRedisTemplate redis,
      @Value("${gateway.rate-limiter.max-requests:60}") int maxRequests,
      @Value("${gateway.rate-limiter.window-seconds:60}") long windowSeconds,
      @Value("${gateway.rate-limiter.trusted-proxies:127.0.0.1,::1}") String trustedProxiesRaw) {
    this.redis = redis;
    this.maxRequests = maxRequests;
    this.windowSeconds = windowSeconds;
    this.trustedProxiesRaw = trustedProxiesRaw;
  }

  @PostConstruct
  void init() {
    trustedProxies =
        Arrays.stream(trustedProxiesRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    log.info("RATE_LIMIT | trusted proxies: {}", trustedProxies);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (!path.startsWith("/llm")) {
      return chain.filter(exchange);
    }

    String clientIp = resolveClientIp(exchange);
    long windowBucket = Instant.now().getEpochSecond() / windowSeconds;
    String key = KEY_PREFIX + clientIp + ":" + windowBucket;

    return redis
        .opsForValue()
        .increment(key)
        .flatMap(
            count -> {
              if (count == 1L) {
                return redis.expire(key, Duration.ofSeconds(windowSeconds * 2)).thenReturn(count);
              }
              return Mono.just(count);
            })
        .flatMap(
            count -> {
              exchange
                  .getResponse()
                  .getHeaders()
                  .set("X-RateLimit-Limit", String.valueOf(maxRequests));
              exchange
                  .getResponse()
                  .getHeaders()
                  .set("X-RateLimit-Remaining", String.valueOf(Math.max(0L, maxRequests - count)));

              if (count > maxRequests) {
                log.warn(
                    "RATE_LIMIT | Blocked | ip={} | count={}/{}", clientIp, count, maxRequests);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange
                    .getResponse()
                    .getHeaders()
                    .set("Retry-After", String.valueOf(windowSeconds));
                return exchange.getResponse().setComplete();
              }
              return chain.filter(exchange);
            })
        .onErrorResume(
            ex -> {
              log.error("RATE_LIMIT | Redis error, allowing request | {}", ex.getMessage());
              return chain.filter(exchange);
            });
  }

  /**
   * Returns the client IP. X-Forwarded-For is only honoured when the actual remote address is in
   * the trusted-proxies list — this prevents header spoofing by untrusted clients.
   */
  private String resolveClientIp(ServerWebExchange exchange) {
    InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
    String remoteIp =
        remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";

    if (trustedProxies.contains(remoteIp)) {
      String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        return forwarded.split(",")[0].trim();
      }
    }
    return remoteIp;
  }
}
