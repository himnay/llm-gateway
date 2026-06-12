package com.llm.gateway.llm_gateway.guardrail.chain;

import org.springframework.core.Ordered;

/**
 * One handler in the gateway's inbound guardrail pipeline
 * (GoF <b>Chain of Responsibility</b>).
 *
 * <p>Implementations are discovered automatically: declare a {@code @Component}
 * implementing this interface and Spring injects it into the {@link GuardrailChain},
 * sorted by {@link #getOrder()} (lower runs first). Reserved bands:</p>
 * <ul>
 *   <li>{@code 100} — prompt sanitization / injection blocking</li>
 *   <li>{@code 200} — sensitive-data (PII/secret) redaction</li>
 *   <li>{@code 300} — remote guardrails service (LangChain sidecar)</li>
 * </ul>
 *
 * <p>A step either lets the request continue (optionally rewriting the prompt via
 * {@link GuardrailContext#updatePrompt(String)}) or stops the chain by throwing
 * {@link com.llm.gateway.llm_gateway.security.PromptValidationException}.</p>
 */
public interface GuardrailStep extends Ordered {

    /** Short, stable name used in logs, metrics and audit events. */
    String name();

    /**
     * Applies this guardrail to the request in flight.
     *
     * @throws com.llm.gateway.llm_gateway.security.PromptValidationException
     *         to reject the request (mapped to HTTP 400)
     */
    void apply(GuardrailContext context);
}
