package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * OUTPUT guardrail — async heuristic hallucination detection.
 * Zero latency impact: the check runs fire-and-forget after the response is returned.
 *
 * NOTE: This is a heuristic signal (uncertainty phrases + knowledge-cutoff signals).
 * It does not detect all hallucinations and produces false positives for legitimate
 * epistemic hedging. For precise factual grounding, consider retrieval-augmented
 * generation (RAG) or dedicated fact-checking services.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HallucinationMonitorAdvisor implements BaseAdvisor {

    private static final double THRESHOLD = 1.5;

    private static final List<String> UNCERTAINTY = List.of(
            "i'm not sure", "i think", "i believe", "probably", "might be",
            "i cannot confirm", "i'm not certain", "i may be wrong",
            "to the best of my knowledge", "as far as i know", "i'm unsure"
    );

    private static final List<String> SIGNALS = List.of(
            "my knowledge cutoff", "i don't have access to real-time",
            "as of my last update", "i cannot access current", "please verify",
            "you should double-check", "i cannot guarantee accuracy",
            "this may not be accurate", "i don't have real-time information"
    );

    private final LlmMetricsService metricsService;

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 10; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String text = AdvisorUtils.extractResponseText(response);
        if (text == null || text.isBlank()) return response;

        String provider = (String) response.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown");
        Mono.fromRunnable(() -> analyse(text, provider))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, ex -> log.debug("HallucinationMonitor | error | {}", ex.getMessage()));

        return response;
    }

    private void analyse(String text, String provider) {
        String lower = text.toLowerCase();
        long uncertainty = UNCERTAINTY.stream().filter(lower::contains).count();
        long signals     = SIGNALS.stream().filter(lower::contains).count();
        double score     = uncertainty * 0.5 + signals * 1.0;

        if (score >= THRESHOLD) {
            log.warn("GUARDRAIL | HALLUCINATION_SUSPECTED | provider={} | score={} | uncertainty={} | signals={}",
                    provider, score, uncertainty, signals);
            metricsService.recordError(provider, "HALLUCINATION_SUSPECTED");
        } else if (score > 0) {
            log.debug("GUARDRAIL | MINOR_UNCERTAINTY | provider={} | score={}", provider, score);
        }
    }
}
