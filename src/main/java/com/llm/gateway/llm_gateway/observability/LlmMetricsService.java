package com.llm.gateway.llm_gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Centralised metrics service for the LLM Gateway.
 *
 * <h3>Exposed metrics (Prometheus endpoint: /llm/actuator/prometheus)</h3>
 * <ul>
 *   <li>{@code llm_requests_total}               – counters per provider, cache-hit flag</li>
 *   <li>{@code llm_provider_calls_total}         – calls per provider + model + outcome (success/error)</li>
 *   <li>{@code llm_requests_errors_total}         – counters per provider, error type</li>
 *   <li>{@code llm_requests_rejected_total}       – counters per provider, reason</li>
 *   <li>{@code llm_request_latency_seconds}       – timer histogram per provider</li>
 *   <li>{@code llm_prompt_length_chars}           – distribution summary</li>
 *   <li>{@code llm_tokens_total}                  – token usage per provider + model + type (prompt/completion/total)</li>
 * </ul>
 */
@Slf4j
@Service
public class LlmMetricsService {

    private final MeterRegistry meterRegistry;

    // Pre-registered timers / counters for hot-path performance
    private static final String METRIC_REQUESTS   = "llm.requests.total";
    private static final String METRIC_PROVIDER_CALLS = "llm.provider.calls.total";
    private static final String METRIC_ERRORS     = "llm.requests.errors.total";
    private static final String METRIC_REJECTED   = "llm.requests.rejected.total";
    private static final String METRIC_LATENCY    = "llm.request.latency.seconds";
    private static final String METRIC_PROMPT_LEN = "llm.prompt.length.chars";
    private static final String METRIC_TOKENS     = "llm.tokens.total";

    public LlmMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Request tracking
    // ──────────────────────────────────────────────────────────────────────────

    /** Increments the total-request counter. */
    public void recordRequest(String provider, boolean cacheHit) {
        Counter.builder(METRIC_REQUESTS)
               .description("Total LLM requests processed by the gateway")
               .tag("provider", provider)
               .tag("cache_hit", String.valueOf(cacheHit))
               .register(meterRegistry)
               .increment();
    }

    /**
     * Dedicated per-provider call counter so dashboards can show exactly how many
     * LLM calls were routed to each provider/model and whether they succeeded.
     *
     * <p>Metric: {@code llm_provider_calls_total{provider, model, outcome}}.</p>
     *
     * @param outcome {@code "success"} or {@code "error"}
     */
    public void recordProviderCall(String provider, String model, String outcome) {
        Counter.builder(METRIC_PROVIDER_CALLS)
               .description("LLM calls routed to each provider, partitioned by model and outcome")
               .tag("provider", provider)
               .tag("model",    model    == null || model.isBlank() ? "default" : model)
               .tag("outcome",  outcome)
               .register(meterRegistry)
               .increment();
    }

    /**
     * Records token consumption reported by the provider, broken down by type
     * so usage and cost can be tracked per provider/model.
     *
     * <p>Metric: {@code llm_tokens_total{provider, model, type}} where {@code type}
     * is {@code prompt}, {@code completion} or {@code total}.</p>
     */
    public void recordTokenUsage(String provider, String model,
                                 Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        recordTokens(provider, model, "prompt",     promptTokens);
        recordTokens(provider, model, "completion", completionTokens);
        recordTokens(provider, model, "total",      totalTokens);
    }

    private void recordTokens(String provider, String model, String type, Integer count) {
        if (count == null || count <= 0) return;
        Counter.builder(METRIC_TOKENS)
               .description("Total tokens consumed, partitioned by provider, model and type")
               .tag("provider", provider)
               .tag("model",    model == null || model.isBlank() ? "default" : model)
               .tag("type",     type)
               .register(meterRegistry)
               .increment(count);
    }

    /** Records the end-to-end latency of a single LLM call. */
    public void recordLatency(String provider, long latencyMs) {
        Timer.builder(METRIC_LATENCY)
             .description("LLM provider call latency")
             .tag("provider", provider)
             .publishPercentileHistogram()
             .register(meterRegistry)
             .record(latencyMs, TimeUnit.MILLISECONDS);

        log.debug("METRICS | latency recorded | provider={} | latencyMs={}", provider, latencyMs);
    }

    /** Tracks the distribution of prompt lengths (useful for capacity planning). */
    public void recordPromptLength(String provider, int length) {
        meterRegistry.summary(METRIC_PROMPT_LEN,
                              "provider", provider)
                     .record(length);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cache tracking
    // ──────────────────────────────────────────────────────────────────────────

    public void recordCacheHit(String provider) {
        recordRequest(provider, true);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Error tracking
    // ──────────────────────────────────────────────────────────────────────────

    /** Increments the error counter with the exception type as a tag. */
    public void recordError(String provider, String errorType) {
        Counter.builder(METRIC_ERRORS)
               .description("Total LLM request errors")
               .tag("provider", provider)
               .tag("error_type", errorType)
               .register(meterRegistry)
               .increment();
    }

    /**
     * Records that sensitive data (PII or a secret) was redacted from a prompt or
     * response. Metric: {@code llm_sensitive_data_redactions_total{provider, direction, type}}
     * where {@code direction} is {@code inbound} (to the LLM) or {@code outbound} (to the caller).
     */
    public void recordSensitiveDataRedaction(String provider, String direction, Iterable<String> types) {
        for (String type : types) {
            Counter.builder("llm.sensitive.data.redactions.total")
                   .description("Sensitive data (PII/secrets) redacted from prompts or responses")
                   .tag("provider",  provider)
                   .tag("direction", direction)
                   .tag("type",      type)
                   .register(meterRegistry)
                   .increment();
        }
    }

    /** Increments the rejection counter (e.g. prompt injection detected). */
    public void recordRejectedRequest(String provider, String reason) {
        Counter.builder(METRIC_REJECTED)
               .description("Total LLM requests rejected by security policy")
               .tag("provider", provider)
               .tag("reason", reason)
               .register(meterRegistry)
               .increment();

        log.info("METRICS | request rejected | provider={} | reason={}", provider, reason);
    }
}

