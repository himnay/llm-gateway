package com.llm.gateway.llm_gateway.guardrail.remote;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the external LangChain guardrails sidecar
 * (bound from {@code llm.guardrails.external.*}).
 */
@Component
@ConfigurationProperties(prefix = "llm.guardrails.external")
@Data
public class RemoteGuardrailProperties {

    /** Master switch — when false the gateway never calls the sidecar. */
    private boolean enabled = true;

    /** Base URL of the guardrails service (docker-compose service: guardrails). */
    private String baseUrl = "http://localhost:8000";

    /** Per-call timeout; keep small — this sits on the hot path of every request. */
    private long timeoutMs = 3000;

    /**
     * Availability policy when the sidecar is down/slow:
     * {@code true} = fail-open (let the request continue, log + metric),
     * {@code false} = fail-closed (reject the request).
     */
    private boolean failOpen = true;

    /** Also validate LLM responses (stage "output") before returning them to the caller. */
    private boolean validateOutput = false;
}
