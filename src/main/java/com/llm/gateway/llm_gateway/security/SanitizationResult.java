package com.llm.gateway.llm_gateway.security;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** Holds the result of running the {@link PromptSanitizer} over a raw prompt. */
@Data
@Builder
public class SanitizationResult {

  /** True when the prompt passed all hard-block rules and may be forwarded. */
  private boolean valid;

  /** True when the sanitizer modified the prompt text. */
  private boolean modified;

  /** The (possibly cleaned-up) prompt that should be sent to the LLM. */
  private String sanitizedPrompt;

  /** Hard violations that caused the request to be rejected. */
  private List<String> violations;

  /** Soft warnings (content was sanitized but the request is still allowed). */
  private List<String> warnings;
}
