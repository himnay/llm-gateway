package com.llm.gateway.llm_gateway.guardrail;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm.guardrails.hallucination")
public class HallucinationGuardProperties {

    /** Enable or disable the hallucination heuristic advisor entirely. */
    private boolean enabled = true;

    /**
     * Score threshold above which the response is considered suspicious.
     * Score = (uncertainty phrase count × 0.5) + (knowledge-cutoff signal count × 1.0).
     */
    private double threshold = 1.5;

    /**
     * When true, responses that exceed the threshold are blocked — the original text is replaced
     * with {@link #blockMessage} and a {@code hallucination_blocked=true} flag is set in context.
     * When false (default), only a WARN is logged and a {@code hallucination_suspected=true}
     * metadata flag is added.
     */
    private boolean blockOnSuspicion = false;

    /** Message returned to the caller when a response is blocked. */
    private String blockMessage =
            "Response blocked: the model expressed high uncertainty about this answer. "
                    + "Please rephrase your question or consult a verified source.";
}
