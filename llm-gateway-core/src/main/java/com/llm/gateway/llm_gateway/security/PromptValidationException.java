package com.llm.gateway.llm_gateway.security;

import java.util.List;

/**
 * Thrown when the {@link PromptSanitizer} detects a hard violation in the user prompt. Results in
 * HTTP 400 Bad Request.
 */
public class PromptValidationException extends RuntimeException {

  private final List<String> violations;

  public PromptValidationException(List<String> violations) {
    super("Prompt validation failed: " + violations);
    this.violations = violations;
  }

  public List<String> getViolations() {
    return violations;
  }
}
