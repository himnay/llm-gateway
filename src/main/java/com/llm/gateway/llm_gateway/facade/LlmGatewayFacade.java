package com.llm.gateway.llm_gateway.facade;

import com.llm.gateway.llm_gateway.cache.PromptCacheService;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptSanitizer;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.security.SanitizationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM Gateway Facade – single entry point for all LLM interactions.
 *
 * Request pipeline:
 *   1. Resolve provider
 *   2. Sanitize prompt (PromptSanitizer)
 *   3. Redis cache lookup
 *   4. Open Micrometer Observation span → Zipkin
 *   5. Enrich MDC (traceId, spanId, requestId, provider)
 *   6. Delegate to provider (with Resilience4j circuit-breaker + retry + rate-limiter)
 *   7. Attach observability metadata to response
 *   8. Store in cache on success
 *   9. Record gateway-level metrics
 */
@Slf4j
@Service
public class LlmGatewayFacade {

    private final Map<String, LlmServiceProvider> providers;
    private final PromptSanitizer sanitizer;
    private final PromptCacheService cacheService;
    private final LlmMetricsService metricsService;
    private final ObservationRegistry observationRegistry;
    @Autowired(required = false)
    @Nullable
    private Tracer tracer;

    public LlmGatewayFacade(List<LlmServiceProvider> providerList,
                             PromptSanitizer sanitizer,
                             PromptCacheService cacheService,
                             LlmMetricsService metricsService,
                             ObservationRegistry observationRegistry) {
        this.sanitizer           = sanitizer;
        this.cacheService        = cacheService;
        this.metricsService      = metricsService;
        this.observationRegistry = observationRegistry;
        this.providers           = providerList.stream()
                .collect(Collectors.toMap(
                        p -> p.getProviderName().toLowerCase(),
                        Function.identity()));
    }

    @PostConstruct
    void logRegisteredProviders() {
        log.info("LLM Gateway initialised with {} providers: {}", providers.size(), providers.keySet());
    }

    @Retry(name = "llm-gateway", fallbackMethod = "retryFallback")
    @CircuitBreaker(name = "llm-gateway", fallbackMethod = "circuitBreakerFallback")
    public LlmResponse execute(String providerName, LlmRequest request) {
        long   startTime = System.currentTimeMillis();
        String reqId     = UUID.randomUUID().toString();
        String provider  = providerName.toLowerCase();

        MDC.put("requestId", reqId);
        MDC.put("provider",  provider);
        if (request.getSessionId() != null) {
            MDC.put("sessionId", request.getSessionId());
        }

        try {
            // ── 1. Resolve provider ───────────────────────────────────────────
            LlmServiceProvider providerBean = resolveProvider(provider);

            // ── 2. Prompt sanitization (gateway-level guard) ──────────────────
            SanitizationResult sanitization = sanitizer.sanitize(request.getPrompt());
            if (!sanitization.isValid()) {
                metricsService.recordRejectedRequest(provider, "INJECTION_DETECTED");
                log.warn("SECURITY | Prompt rejected | requestId={} | violations={}",
                         reqId, sanitization.getViolations());
                throw new PromptValidationException(sanitization.getViolations());
            }
            if (sanitization.isModified()) {
                log.info("SECURITY | Prompt sanitized | requestId={} | warnings={}",
                         reqId, sanitization.getWarnings());
                request.setPrompt(sanitization.getSanitizedPrompt());
            }
            metricsService.recordPromptLength(provider, request.getPrompt().length());

            // ── 3. Cache look-up ──────────────────────────────────────────────
            Optional<LlmResponse> cached = cacheService.get(provider, request);
            if (cached.isPresent()) {
                LlmResponse hit = cached.get();
                hit.setCacheHit(true);
                hit.setRequestId(reqId);
                hit.setSessionId(request.getSessionId());
                hit.setLatencyMs(System.currentTimeMillis() - startTime);
                metricsService.recordCacheHit(provider);
                log.info("CACHE | Serving cached response | requestId={} | provider={}", reqId, provider);
                return hit;
            }

            // ── 4. Open Observation span ──────────────────────────────────────
            Observation observation = Observation.createNotStarted("llm.request", observationRegistry)
                    .contextualName("LLM " + provider + " call")
                    .lowCardinalityKeyValue("provider", provider)
                    .lowCardinalityKeyValue("model", request.getModel() != null ? request.getModel() : "default")
                    .highCardinalityKeyValue("prompt.length", String.valueOf(request.getPrompt().length()))
                    .highCardinalityKeyValue("request.id", reqId)
                    .start();

            LlmResponse response;
            try {
                // ── 5. Enrich MDC with trace IDs ──────────────────────────────
                String traceId = MDC.get("traceId") != null ? MDC.get("traceId") : "unknown";
                String spanId  = MDC.get("spanId")  != null ? MDC.get("spanId")  : "unknown";
                if (tracer != null) {
                    Span currentSpan = tracer.currentSpan();
                    if (currentSpan != null) {
                        traceId = currentSpan.context().traceId();
                        spanId  = currentSpan.context().spanId();
                    }
                }
                MDC.put("traceId", traceId);
                MDC.put("spanId",  spanId);

                log.info("LLM | Calling provider | requestId={} | traceId={} | provider={} | promptLen={}",
                         reqId, traceId, provider, request.getPrompt().length());

                // ── 6. Delegate to provider ───────────────────────────────────
                response = providerBean.execute(request);

                // ── 7. Attach observability metadata ──────────────────────────
                response.setTraceId(traceId);
                response.setSpanId(spanId);
                response.setRequestId(reqId);
                response.setSessionId(request.getSessionId());
                response.setSanitized(sanitization.isModified());
                response.setCacheHit(false);
                response.setLatencyMs(System.currentTimeMillis() - startTime);

                observation.event(Observation.Event.of("llm.response.received"));
                log.info("LLM | Response received | requestId={} | provider={} | latencyMs={} | error={}",
                         reqId, provider, response.getLatencyMs(), response.getError());

            } catch (Exception ex) {
                observation.error(ex);
                metricsService.recordError(provider, ex.getClass().getSimpleName());
                log.error("LLM | Provider call failed | requestId={} | provider={}", reqId, provider, ex);
                throw ex;
            } finally {
                observation.stop();
            }

            // ── 8. Store in cache on success ──────────────────────────────────
            if (response.getError() == null) {
                cacheService.put(provider, request, response);
            }

            // ── 9. Record gateway-level metrics ───────────────────────────────
            metricsService.recordRequest(provider, false);
            metricsService.recordLatency(provider, response.getLatencyMs());
            if (response.getError() != null) {
                metricsService.recordError(provider, "LLM_ERROR");
            }

            return response;

        } finally {
            MDC.remove("requestId");
            MDC.remove("provider");
            MDC.remove("sessionId");
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }

    /**
     * Tries each provider in {@code providerChain} in order, returning the first
     * successful response. If all providers fail, returns an error response
     * listing the exhausted chain.
     *
     * Each attempt goes through the full pipeline (sanitize → cache → CB/retry).
     */
    public LlmResponse executeWithFailover(List<String> providerChain, LlmRequest request) {
        Exception lastException = null;
        for (String provider : providerChain) {
            try {
                log.info("FAILOVER | Trying provider={} | chain={}", provider, providerChain);
                LlmResponse response = execute(provider, request);
                if (response.getError() == null) {
                    response.setProvider(provider + " (failover)");
                    return response;
                }
                log.warn("FAILOVER | Provider={} returned error={}, trying next", provider, response.getError());
            } catch (Exception ex) {
                log.warn("FAILOVER | Provider={} threw exception={}, trying next", provider, ex.getMessage());
                lastException = ex;
            }
        }
        metricsService.recordError("failover", "ALL_PROVIDERS_EXHAUSTED");
        String detail = lastException != null ? lastException.getMessage() : "all providers returned errors";
        return errorResponse("failover", "All providers exhausted " + providerChain + ": " + detail);
    }

    public LlmResponse circuitBreakerFallback(String providerName, LlmRequest request, Throwable ex) {
        log.warn("CIRCUIT_OPEN | Provider={} | {}", providerName, ex.getMessage());
        metricsService.recordError(providerName, "CIRCUIT_OPEN");
        return errorResponse(providerName, "Provider temporarily unavailable (circuit open). Please retry later.");
    }

    public LlmResponse retryFallback(String providerName, LlmRequest request, Throwable ex) {
        log.error("RETRY_EXHAUSTED | Provider={} | {}", providerName, ex.getMessage());
        metricsService.recordError(providerName, "RETRY_EXHAUSTED");
        return errorResponse(providerName, "Provider failed after multiple retries: " + ex.getMessage());
    }

    public Set<String> getRegisteredProviders() {
        return providers.keySet();
    }

    private LlmServiceProvider resolveProvider(String name) {
        LlmServiceProvider p = providers.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                "Unknown LLM provider: '" + name + "'. Registered: " + providers.keySet());
        }
        return p;
    }

    private LlmResponse errorResponse(String provider, String message) {
        return LlmResponse.builder()
                .provider(provider)
                .error(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
