package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.security.SensitiveDataRedactor;
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

import java.util.Set;

/**
 * INPUT + OUTPUT guardrail — PII / sensitive-data detection and redaction for the
 * Spring AI ChatClient providers (OpenAI, Anthropic, Ollama).
 *
 * INPUT  : replaces detected PII/secrets with typed placeholders before the LLM sees it.
 * OUTPUT : async scan to flag any leakage in the response (non-blocking).
 *
 * <p>Detection logic lives in {@link SensitiveDataRedactor} (single source of truth);
 * the gateway facade applies the same redactor to <b>all</b> providers, so this advisor
 * is defence-in-depth for the ChatClient path.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiRedactionAdvisor implements BaseAdvisor {

    private final SensitiveDataRedactor redactor;

    @Value("${llm.guardrails.pii-redaction.enabled:true}")
    private boolean enabled;

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 2; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) return request;

        String text = AdvisorUtils.extractUserText(request);
        if (text == null || text.isBlank()) return request;

        SensitiveDataRedactor.Result result = redactor.redact(text);
        if (!result.redacted()) return request;

        log.info("GUARDRAIL | PII_REDACTED | types={}", result.types());
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(
                        m -> m.mutate().text(result.text()).build()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) return response;

        String text = AdvisorUtils.extractResponseText(response);
        if (text == null || text.isBlank()) return response;

        String provider = (String) response.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown");
        Mono.fromRunnable(() -> scanOutput(text, provider))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, ex -> log.debug("PiiRedactionAdvisor | scan error | {}", ex.getMessage()));

        return response;
    }

    private void scanOutput(String text, String provider) {
        Set<String> leaked = redactor.detect(text);
        if (!leaked.isEmpty()) {
            log.warn("GUARDRAIL | PII_LEAK_IN_RESPONSE | provider={} | types={}", provider, leaked);
        }
    }
}
