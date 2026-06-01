package com.llm.gateway.llm_gateway.facade;

import com.llm.gateway.llm_gateway.cache.PromptCacheService;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.dto.StructuredLlmResponse;
import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptSanitizer;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.security.SanitizationResult;
import com.llm.gateway.llm_gateway.service.OpenAiService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM Gateway Facade — single entry point for all LLM interactions.
 *
 * Request pipeline:
 *   1. Resolve provider
 *   2. Sanitize prompt
 *   3. Redis cache lookup
 *   4. Open Micrometer Observation span
 *   5. Enrich MDC (traceId, spanId, requestId, provider)
 *   6. Delegate to provider (with Resilience4j circuit-breaker + retry)
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

    // Injected directly for structured-output extraction (OpenAI-only feature)
    @Autowired(required = false)
    @Nullable
    private OpenAiService openAiService;

    @Value("${llm.auto-failover.enabled:true}")
    private boolean autoFailoverEnabled;

    @Value("${llm.auto-failover.order:openai,anthropic,google,cohere,huggingface,ollama}")
    private String autoFailoverOrderRaw;

    @Value("${llm.auto-failover.provider-timeout-seconds:10}")
    private int providerTimeoutSeconds;

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
        // Honour incoming correlation ID from the handler (X-Request-ID header)
        String reqId    = request.getCorrelationId() != null
                ? request.getCorrelationId() : UUID.randomUUID().toString();
        String provider  = providerName.toLowerCase();

        MDC.put("requestId", reqId);
        MDC.put("provider",  provider);
        if (request.getSessionId() != null) MDC.put("sessionId", request.getSessionId());

        try {
            // ── 1. Resolve provider ───────────────────────────────────────────
            LlmServiceProvider providerBean = resolveProvider(provider);

            // ── 2. Prompt sanitization (gateway-level guard) ──────────────────
            SanitizationResult sanitization = sanitizer.sanitize(request.getPrompt());
            if (!sanitization.isValid()) {
                metricsService.recordRejectedRequest(provider, "INJECTION_DETECTED");
                log.warn("SECURITY | Prompt rejected | requestId={} | violations={}", reqId, sanitization.getViolations());
                throw new PromptValidationException(sanitization.getViolations());
            }
            if (sanitization.isModified()) {
                log.info("SECURITY | Prompt sanitized | requestId={} | warnings={}", reqId, sanitization.getWarnings());
                request.setPrompt(sanitization.getSanitizedPrompt());
            }
            metricsService.recordPromptLength(provider, request.getPrompt().length());

            // ── 3. Cache look-up ──────────────────────────────────────────────
            Optional<LlmResponse> cached = cacheService.get(provider, request);
            if (cached.isPresent()) {
                LlmResponse hit = cached.get();
                hit.setCacheHit(true);
                hit.setRequestId(reqId);
                hit.setCorrelationId(reqId);
                hit.setSessionId(request.getSessionId());
                hit.setLatencyMs(System.currentTimeMillis() - startTime);
                metricsService.recordCacheHit(provider);
                log.info("CACHE | HIT | requestId={} | provider={}", reqId, provider);
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
                response.setCorrelationId(reqId);
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
     * Tries each provider in order, returning the first successful response.
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

    /**
     * Executes against the preferred provider; if the provider fails with an
     * auth/config/connectivity error AND auto-failover is enabled, the gateway
     * silently logs the failure and retries each provider in
     * {@code llm.auto-failover.order} until one succeeds.
     *
     * Handles two failure modes:
     *  - Thrown exception (provider SDK throws directly)
     *  - Soft error (service catches internally and returns LlmResponse.error)
     *
     * Client prompt rejections (injection detected, topic blocked) are never
     * failed-over — they always propagate back to the caller.
     */
    public LlmResponse executeWithAutoFailover(String preferredProvider, LlmRequest request) {
        LlmResponse primaryResponse = null;
        boolean failoverNeeded = false;

        try {
            primaryResponse = execute(preferredProvider, request);

            // Services catch exceptions internally and return them as LlmResponse.error.
            // Detect auth/config errors in the soft-error field and trigger failover.
            if (primaryResponse.getError() != null && isFailoverWorthy(primaryResponse.getError())) {
                log.warn("AUTO_FAILOVER | provider={} returned auth/config error — trying alternatives | error={}",
                        preferredProvider, primaryResponse.getError());
                metricsService.recordError(preferredProvider, "AUTO_FAILOVER_TRIGGERED");
                failoverNeeded = true;
            }
        } catch (Exception ex) {
            if (!autoFailoverEnabled || !isFailoverWorthy(ex)) {
                throw ex;
            }
            log.warn("AUTO_FAILOVER | provider={} threw {} — trying alternatives | reason={}",
                    preferredProvider, ex.getClass().getSimpleName(), rootMessage(ex));
            metricsService.recordError(preferredProvider, "AUTO_FAILOVER_TRIGGERED");
            failoverNeeded = true;
        }

        if (!autoFailoverEnabled || !failoverNeeded) {
            return primaryResponse;
        }

        // Try each provider in the configured failover order, skipping the failed one
        List<String> order = Arrays.stream(autoFailoverOrderRaw.split(","))
                .map(String::trim).map(String::toLowerCase).toList();

        for (String candidate : order) {
            if (candidate.equalsIgnoreCase(preferredProvider)) continue;
            if (!providers.containsKey(candidate)) continue;

            log.info("AUTO_FAILOVER | trying provider={} (timeout={}s)", candidate, providerTimeoutSeconds);
            try {
                // Per-provider timeout prevents one slow/hung provider from
                // consuming the entire gateway timeout and blocking the chain.
                LlmResponse candidateResponse = CompletableFuture
                        .supplyAsync(() -> execute(candidate, request))
                        .orTimeout(providerTimeoutSeconds, TimeUnit.SECONDS)
                        .get();

                if (candidateResponse.getError() == null) {
                    log.info("AUTO_FAILOVER | succeeded with provider={}", candidate);
                    candidateResponse.setProvider(candidateResponse.getProvider()
                            + " [auto-failover from " + preferredProvider + "]");
                    return candidateResponse;
                }

                if (isFailoverWorthy(candidateResponse.getError())) {
                    log.warn("AUTO_FAILOVER | provider={} failed, continuing | error={}",
                            candidate, candidateResponse.getError());
                    continue;
                }

                // Provider returned a non-retryable error (e.g. prompt too long)
                return candidateResponse;

            } catch (ExecutionException ee) {
                // orTimeout() wraps TimeoutException inside ExecutionException
                if (ee.getCause() instanceof java.util.concurrent.TimeoutException) {
                    log.warn("AUTO_FAILOVER | provider={} timed out after {}s, skipping", candidate, providerTimeoutSeconds);
                    metricsService.recordError(candidate, "FAILOVER_TIMEOUT");
                    continue;
                }
                Exception cause = ee.getCause() instanceof Exception ex ? ex : ee;
                if (isFailoverWorthy(cause)) {
                    log.warn("AUTO_FAILOVER | provider={} failed: {}", candidate, rootMessage(cause));
                } else {
                    throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        metricsService.recordError("auto-failover", "ALL_PROVIDERS_EXHAUSTED");
        return errorResponse(preferredProvider,
                "All providers exhausted during auto-failover. Check API key configurations.");
    }

    /**
     * True when a thrown exception should trigger failover to the next provider.
     *
     * Strategy: failover on ANY provider-side error (HTTP 4xx/5xx, connection
     * problems, SDK exceptions). Only client-side prompt rejections stop the chain
     * — those will fail identically on every provider.
     */
    private boolean isFailoverWorthy(Exception ex) {
        // Client errors — the same prompt will be rejected everywhere, don't failover
        if (ex instanceof PromptValidationException) return false;
        if (ex instanceof IllegalArgumentException)  return false;

        // Every other exception is a provider-side problem → try the next one
        return true;
    }

    /**
     * True when a soft-error string (LlmResponse.error) should trigger failover.
     *
     * Prompt-rejection messages come from our own guardrail advisors and include
     * specific keywords. Everything else is a provider-side failure.
     */
    private boolean isFailoverWorthy(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) return false;
        String msg = errorMsg.toLowerCase();
        // These originate from our guardrail chain — the prompt is the problem, not the provider
        if (msg.contains("prompt validation")
                || msg.contains("injection detected")
                || msg.contains("toxic content")
                || msg.contains("restricted topic")
                || msg.contains("harmful")) {
            return false;
        }
        // Any other error from a provider → failover
        return true;
    }

    private static String rootMessage(Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage()
                : ex.getMessage() != null ? ex.getMessage()
                : ex.getClass().getSimpleName();
    }

    /**
     * Structured-output extraction via OpenAI's BeanOutputConverter.
     * Runs inside an Observation span for full tracing coverage.
     */
    public StructuredLlmResponse executeStructured(LlmRequest request) {
        if (openAiService == null) {
            throw new IllegalStateException("OpenAI provider is not available for structured output extraction");
        }
        String reqId = request.getCorrelationId() != null
                ? request.getCorrelationId() : UUID.randomUUID().toString();
        MDC.put("requestId", reqId);
        try {
            Observation obs = Observation.createNotStarted("llm.structured", observationRegistry)
                    .contextualName("LLM structured extraction")
                    .lowCardinalityKeyValue("provider", "openai")
                    .start();
            try {
                return openAiService.extractStructured(request, StructuredLlmResponse.class);
            } catch (Exception ex) {
                obs.error(ex);
                throw ex;
            } finally {
                obs.stop();
            }
        } finally {
            MDC.remove("requestId");
        }
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
