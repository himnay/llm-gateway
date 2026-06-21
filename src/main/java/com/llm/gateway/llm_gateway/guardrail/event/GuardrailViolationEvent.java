package com.llm.gateway.llm_gateway.guardrail.event;

import java.util.List;

/**
 * Published whenever a {@code GuardrailStep} rejects a request (GoF <b>Observer</b> via Spring's
 * application-event bus).
 *
 * <p>Decouples guardrail enforcement from cross-cutting reactions: audit logging, alerting,
 * ban-listing, etc. can subscribe with {@code @EventListener} without the chain knowing about them.
 *
 * @param provider provider the request was routed to
 * @param requestId correlation id of the rejected request
 * @param step name of the guardrail step that fired
 * @param violations human-readable violation descriptions
 * @param timestamp epoch millis of the rejection
 */
public record GuardrailViolationEvent(
    String provider, String requestId, String step, List<String> violations, long timestamp) {}
