package com.llm.gateway.llm_gateway.guardrail.chain;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptSanitizer;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.security.SanitizationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Chain step 100 — prompt-injection blocking and prompt normalisation. Delegates to {@link
 * PromptSanitizer}; hard violations reject the request, strip/normalisation rewrites flow back into
 * the context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptSanitizationStep implements GuardrailStep {

  private final PromptSanitizer sanitizer;
  private final LlmMetricsService metricsService;

  @Override
  public String name() {
    return "prompt-sanitization";
  }

  @Override
  public int getOrder() {
    return 100;
  }

  @Override
  public void apply(GuardrailContext context) {
    SanitizationResult result = sanitizer.sanitize(context.getPrompt());
    if (!result.isValid()) {
      metricsService.recordRejectedRequest(context.getProvider(), "INJECTION_DETECTED");
      log.warn(
          "SECURITY | Prompt rejected | requestId={} | violations={}",
          context.getRequestId(),
          result.getViolations());
      throw new PromptValidationException(result.getViolations());
    }
    if (result.isModified()) {
      log.info(
          "SECURITY | Prompt sanitized | requestId={} | warnings={}",
          context.getRequestId(),
          result.getWarnings());
      context.updatePrompt(result.getSanitizedPrompt());
      context.addWarnings(result.getWarnings());
    }
  }
}
