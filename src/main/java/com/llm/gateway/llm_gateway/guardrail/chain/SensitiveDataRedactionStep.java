package com.llm.gateway.llm_gateway.guardrail.chain;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.SensitiveDataRedactor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chain step 200 — provider-agnostic PII/secret redaction of the inbound prompt.
 * Runs for EVERY provider — including the custom REST ones (Google, Cohere,
 * HuggingFace) that bypass the Spring AI advisor chain — so no PII or secret is
 * ever forwarded to an LLM, cached, or logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveDataRedactionStep implements GuardrailStep {

    private final SensitiveDataRedactor redactor;
    private final LlmMetricsService metricsService;

    @Value("${llm.security.sensitive-data.enabled:true}")
    private boolean enabled;

    @Value("${llm.security.sensitive-data.redact-prompt:true}")
    private boolean redactPrompt;

    @Override
    public String name() {
        return "sensitive-data-redaction";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void apply(GuardrailContext context) {
        if (!enabled || !redactPrompt) {
            return;
        }
        SensitiveDataRedactor.Result redaction = redactor.redact(context.getPrompt());
        if (redaction.redacted()) {
            context.updatePrompt(redaction.text());
            metricsService.recordSensitiveDataRedaction(context.getProvider(), "inbound", redaction.types());
            log.info("SECURITY | Sensitive data redacted from prompt | requestId={} | types={}",
                    context.getRequestId(), redaction.types());
        }
    }
}
