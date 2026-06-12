package com.llm.gateway.llm_gateway.guardrail.chain;

import com.llm.gateway.llm_gateway.guardrail.remote.GuardrailValidationResult;
import com.llm.gateway.llm_gateway.guardrail.remote.RemoteGuardrailClient;
import com.llm.gateway.llm_gateway.guardrail.remote.RemoteGuardrailProperties;
import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Chain step 300 — delegates prompt validation to the external LangChain guardrails
 * sidecar (REST) before any LLM provider is called. The sidecar runs the heavyweight
 * checks (injection/jailbreak heuristics, toxicity, PII, topic policy, optional
 * LLM-as-judge) that don't belong inside the gateway's hot path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteGuardrailStep implements GuardrailStep {

    private final RemoteGuardrailClient client;
    private final RemoteGuardrailProperties properties;
    private final LlmMetricsService metricsService;

    @Override
    public String name() {
        return "remote-guardrails";
    }

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public void apply(GuardrailContext context) {
        if (!properties.isEnabled()) {
            return;
        }
        GuardrailValidationResult result = client.validate(context.getPrompt(), "input");

        if (!result.passed()) {
            metricsService.recordRejectedRequest(context.getProvider(), "EXTERNAL_GUARDRAIL");
            log.warn("GUARDRAIL | prompt rejected by guardrails service | requestId={} | riskScore={} | violations={}",
                    context.getRequestId(), result.riskScore(), result.violations());
            throw new PromptValidationException(result.violations());
        }
        if (result.sanitizedText() != null) {
            context.updatePrompt(result.sanitizedText());
        }
    }
}
