package com.llm.gateway.llm_gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>{@code llm_tokens_total}                  – token usage per provider + model + type</li>
 * </ul>
 *
 * Meters for well-known providers are pre-registered at startup to avoid per-request
 * registry lookup overhead on the hot path.
 */
@Slf4j
@Service
public class LlmMetricsService {

    private static final String METRIC_REQUESTS       = "llm.requests.total";
    private static final String METRIC_PROVIDER_CALLS = "llm.provider.calls.total";
    private static final String METRIC_ERRORS         = "llm.requests.errors.total";
    private static final String METRIC_REJECTED       = "llm.requests.rejected.total";
    private static final String METRIC_LATENCY        = "llm.request.latency.seconds";
    private static final String METRIC_PROMPT_LEN     = "llm.prompt.length.chars";
    private static final String METRIC_TOKENS         = "llm.tokens.total";

    private static final List<String> KNOWN_PROVIDERS =
            List.of("openai", "anthropic", "ollama", "google", "huggingface", "cohere", "failover", "auto-failover");

    private final MeterRegistry meterRegistry;

    // Pre-registered counter/timer caches — avoids builder overhead on every request.
    private final Map<String, Counter> requestCounters  = new ConcurrentHashMap<>();
    private final Map<String, Timer>   latencyTimers    = new ConcurrentHashMap<>();

    public LlmMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void preRegister() {
        for (String provider : KNOWN_PROVIDERS) {
            for (boolean hit : new boolean[]{true, false}) {
                String key = provider + ":" + hit;
                requestCounters.put(key, Counter.builder(METRIC_REQUESTS)
                        .description("Total LLM requests processed by the gateway")
                        .tag("provider", provider)
                        .tag("cache_hit", String.valueOf(hit))
                        .register(meterRegistry));
            }
            latencyTimers.put(provider, Timer.builder(METRIC_LATENCY)
                    .description("LLM provider call latency")
                    .tag("provider", provider)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
        log.info("METRICS | pre-registered meters for {} providers", KNOWN_PROVIDERS.size());
    }

    public void recordRequest(String provider, boolean cacheHit) {
        String key = provider + ":" + cacheHit;
        Counter c = requestCounters.get(key);
        if (c != null) {
            c.increment();
        } else {
            meterRegistry.counter(METRIC_REQUESTS, "provider", provider, "cache_hit", String.valueOf(cacheHit)).increment();
        }
    }

    public void recordProviderCall(String provider, String model, String outcome) {
        meterRegistry.counter(METRIC_PROVIDER_CALLS,
                "provider", provider,
                "model",    model == null || model.isBlank() ? "default" : model,
                "outcome",  outcome)
                .increment();
    }

    public void recordTokenUsage(String provider, String model,
                                 Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        recordTokens(provider, model, "prompt",     promptTokens);
        recordTokens(provider, model, "completion", completionTokens);
        recordTokens(provider, model, "total",      totalTokens);
    }

    private void recordTokens(String provider, String model, String type, Integer count) {
        if (count == null || count <= 0) return;
        meterRegistry.counter(METRIC_TOKENS,
                "provider", provider,
                "model",    model == null || model.isBlank() ? "default" : model,
                "type",     type)
                .increment(count);
    }

    public void recordLatency(String provider, long latencyMs) {
        Timer t = latencyTimers.get(provider);
        if (t != null) {
            t.record(latencyMs, TimeUnit.MILLISECONDS);
        } else {
            Timer.builder(METRIC_LATENCY)
                 .description("LLM provider call latency")
                 .tag("provider", provider)
                 .publishPercentileHistogram()
                 .register(meterRegistry)
                 .record(latencyMs, TimeUnit.MILLISECONDS);
        }
        log.debug("METRICS | latency recorded | provider={} | latencyMs={}", provider, latencyMs);
    }

    public void recordPromptLength(String provider, int length) {
        meterRegistry.summary(METRIC_PROMPT_LEN, "provider", provider).record(length);
    }

    public void recordCacheHit(String provider) {
        recordRequest(provider, true);
    }

    public void recordError(String provider, String errorType) {
        meterRegistry.counter(METRIC_ERRORS,
                "provider",   provider,
                "error_type", errorType)
                .increment();
    }

    public void recordSensitiveDataRedaction(String provider, String direction, Iterable<String> types) {
        for (String type : types) {
            meterRegistry.counter("llm.sensitive.data.redactions.total",
                    "provider",  provider,
                    "direction", direction,
                    "type",      type)
                    .increment();
        }
    }

    public void recordRejectedRequest(String provider, String reason) {
        meterRegistry.counter(METRIC_REJECTED,
                "provider", provider,
                "reason",   reason)
                .increment();
        log.info("METRICS | request rejected | provider={} | reason={}", provider, reason);
    }
}
