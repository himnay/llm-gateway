package com.llm.gateway.llm_gateway.guardrail.remote;

import java.util.List;

/**
 * Outcome of one validation call against the guardrails sidecar.
 *
 * @param passed        true when no guardrail fired
 * @param violations    human-readable violation descriptions (empty when passed)
 * @param sanitizedText optional rewritten text (e.g. PII masked by the sidecar); may be null
 * @param riskScore     0.0–1.0 aggregate risk reported by the sidecar; -1 when unknown
 */
public record GuardrailValidationResult(boolean passed,
                                        List<String> violations,
                                        String sanitizedText,
                                        double riskScore) {

    public static GuardrailValidationResult passedResult() {
        return new GuardrailValidationResult(true, List.of(), null, -1);
    }
}
