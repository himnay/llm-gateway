package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * INPUT + OUTPUT guardrail — toxic / harmful content detection.
 *
 * INPUT  : BLOCKS the request if toxic content is detected (throws → HTTP 400).
 * OUTPUT : async scan; logs warning and emits metric if toxic content found in response.
 *
 * Uses a configurable keyword/pattern list. In production, replace or augment with
 * an ML-based classifier (e.g. OpenAI Moderation API, Perspective API).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToxicityFilterAdvisor implements BaseAdvisor {

    private static final List<String> TOXIC_PATTERNS = List.of(
            "how to make a bomb", "how to build a weapon", "step by step instructions to kill",
            "synthesize methamphetamine", "create malware", "write a virus",
            "exploit vulnerability", "hack into", "bypass security",
            "child exploitation", "self-harm instructions", "suicide method"
    );

    private final LlmMetricsService metricsService;

    @Value("${llm.guardrails.toxicity.enabled:true}")
    private boolean enabled;

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) return request;

        String text = extractUserText(request);
        if (text == null || text.isBlank()) return request;

        String lower = text.toLowerCase();
        for (String pattern : TOXIC_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("GUARDRAIL | TOXIC_INPUT_BLOCKED | pattern={}", pattern);
                metricsService.recordRejectedRequest(
                        (String) request.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown"),
                        "TOXIC_CONTENT");
                throw new PromptValidationException(List.of("Request blocked: harmful or toxic content detected"));
            }
        }

        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) return response;

        String text = extractResponseText(response);
        if (text == null || text.isBlank()) return response;

        String provider = (String) response.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown");

        Mono.fromRunnable(() -> scanOutput(text, provider))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, ex -> log.debug("ToxicityFilter | scan error | {}", ex.getMessage()));

        return response;
    }

    private void scanOutput(String text, String provider) {
        String lower = text.toLowerCase();
        for (String pattern : TOXIC_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("GUARDRAIL | TOXIC_OUTPUT_DETECTED | provider={} | pattern={}", provider, pattern);
                metricsService.recordError(provider, "TOXIC_OUTPUT_DETECTED");
                return;
            }
        }
    }

    private String extractUserText(ChatClientRequest request) {
        var msg = request.prompt().getUserMessage();
        return msg != null ? msg.getText() : null;
    }

    private String extractResponseText(ChatClientResponse response) {
        if (response.chatResponse() == null) return null;
        Generation result = response.chatResponse().getResult();
        if (result == null || result.getOutput() == null) return null;
        return result.getOutput().getText();
    }
}
