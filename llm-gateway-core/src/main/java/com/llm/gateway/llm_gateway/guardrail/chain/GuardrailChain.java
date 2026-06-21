package com.llm.gateway.llm_gateway.guardrail.chain;

import com.llm.gateway.llm_gateway.guardrail.event.GuardrailViolationEvent;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Inbound guardrail pipeline (GoF <b>Chain of Responsibility</b>).
 *
 * <p>All {@link GuardrailStep} beans are injected in {@link org.springframework.core.Ordered} order
 * and applied sequentially before any LLM provider sees the prompt. A step may rewrite the prompt
 * (sanitization/redaction) and pass the request on, or reject it by throwing {@link
 * PromptValidationException}; rejections are also published as {@link GuardrailViolationEvent}s
 * (GoF <b>Observer</b>) for audit/alerting subscribers.
 */
@Slf4j
@Component
public class GuardrailChain {

  private final List<GuardrailStep> steps;
  private final ApplicationEventPublisher eventPublisher;

  public GuardrailChain(List<GuardrailStep> steps, ApplicationEventPublisher eventPublisher) {
    this.steps = steps; // Spring injects the list pre-sorted by Ordered
    this.eventPublisher = eventPublisher;
  }

  @PostConstruct
  void logChain() {
    log.info(
        "GUARDRAIL | chain initialised with {} step(s): {}",
        steps.size(),
        steps.stream().map(GuardrailStep::name).toList());
  }

  /**
   * Runs every step against the request in {@code context}.
   *
   * @throws PromptValidationException when any step rejects the request
   */
  public void apply(GuardrailContext context) {
    for (GuardrailStep step : steps) {
      try {
        step.apply(context);
      } catch (PromptValidationException ex) {
        eventPublisher.publishEvent(
            new GuardrailViolationEvent(
                context.getProvider(),
                context.getRequestId(),
                step.name(),
                ex.getViolations(),
                System.currentTimeMillis()));
        throw ex;
      }
      log.debug(
          "GUARDRAIL | step passed | step={} | requestId={}", step.name(), context.getRequestId());
    }
  }
}
