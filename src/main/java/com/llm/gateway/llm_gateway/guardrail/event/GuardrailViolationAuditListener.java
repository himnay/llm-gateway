package com.llm.gateway.llm_gateway.guardrail.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Observer that turns {@link GuardrailViolationEvent}s into a structured audit trail
 * (GoF <b>Observer</b>). Loki/Grafana pick these lines up via the {@code AUDIT} marker.
 */
@Slf4j
@Component
public class GuardrailViolationAuditListener {

    @EventListener
    public void onViolation(GuardrailViolationEvent event) {
        log.warn("AUDIT | guardrail violation | step={} | provider={} | requestId={} | violations={}",
                event.step(), event.provider(), event.requestId(), event.violations());
    }
}
