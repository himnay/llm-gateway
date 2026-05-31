package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * OUTPUT guardrail — response format and quality validation.
 * All checks are async (fire-and-forget) to add zero latency to the response path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseFormatAdvisor implements BaseAdvisor {

    private static final List<String> REFUSAL_PATTERNS = List.of(
            "i cannot", "i can't", "i am unable to", "i'm unable to",
            "i won't", "i will not", "i'm not able to", "i refuse",
            "that's not something i can", "i apologize, but i cannot"
    );

    private static final List<String> TRUNCATION_SIGNALS = List.of(
            "...", "…", "[continued]", "[truncated]", "to be continued"
    );

    private final LlmMetricsService metricsService;

    @Value("${llm.guardrails.response-format.enabled:true}")
    private boolean enabled;

    @Value("${llm.guardrails.response-format.min-length:10}")
    private int minLength;

    @Value("${llm.guardrails.response-format.max-length:50000}")
    private int maxLength;

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 20; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) return response;

        String text = AdvisorUtils.extractResponseText(response);
        String provider = (String) response.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown");

        Mono.fromRunnable(() -> validate(text, provider))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, ex -> log.debug("ResponseFormatAdvisor | error | {}", ex.getMessage()));

        return response;
    }

    private void validate(String text, String provider) {
        if (text == null || text.isBlank()) {
            log.warn("GUARDRAIL | EMPTY_RESPONSE | provider={}", provider);
            metricsService.recordError(provider, "EMPTY_RESPONSE");
            return;
        }

        int length = text.length();
        if (length < minLength) {
            log.warn("GUARDRAIL | RESPONSE_TOO_SHORT | provider={} | length={} | min={}", provider, length, minLength);
            metricsService.recordError(provider, "RESPONSE_TOO_SHORT");
        }
        if (length > maxLength) {
            log.warn("GUARDRAIL | RESPONSE_TOO_LONG | provider={} | length={} | max={}", provider, length, maxLength);
            metricsService.recordError(provider, "RESPONSE_TOO_LONG");
        }

        String lower = text.toLowerCase();
        REFUSAL_PATTERNS.stream().filter(lower::startsWith).findFirst().ifPresent(p -> {
            log.warn("GUARDRAIL | REFUSAL_DETECTED | provider={} | pattern={}", provider, p);
            metricsService.recordError(provider, "RESPONSE_REFUSAL");
        });
        TRUNCATION_SIGNALS.stream().filter(s -> lower.endsWith(s.toLowerCase())).findFirst().ifPresent(s ->
                log.warn("GUARDRAIL | TRUNCATION_SUSPECTED | provider={} | signal={}", provider, s));
    }
}
