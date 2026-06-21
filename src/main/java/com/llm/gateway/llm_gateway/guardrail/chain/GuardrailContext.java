package com.llm.gateway.llm_gateway.guardrail.chain;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * Mutable context object passed along the {@link GuardrailChain} (GoF <b>Chain of
 * Responsibility</b>).
 *
 * <p>Each {@link GuardrailStep} can inspect the prompt, rewrite it via {@link
 * #updatePrompt(String)} (sanitization / redaction), attach warnings, or abort the whole request by
 * throwing {@link com.llm.gateway.llm_gateway.security.PromptValidationException}.
 */
@Getter
public class GuardrailContext {

  private final String provider;
  private final String requestId;
  private final LlmRequest request;
  private final List<String> warnings = new ArrayList<>();

  /** True once any step has rewritten the prompt (drives {@code LlmResponse.sanitized}). */
  private boolean promptModified;

  public GuardrailContext(String provider, String requestId, LlmRequest request) {
    this.provider = provider;
    this.requestId = requestId;
    this.request = request;
  }

  public String getPrompt() {
    return request.getPrompt();
  }

  /** Replaces the prompt and flags the request as modified when the text changed. */
  public void updatePrompt(String newPrompt) {
    if (newPrompt != null && !newPrompt.equals(request.getPrompt())) {
      request.setPrompt(newPrompt);
      promptModified = true;
    }
  }

  public void addWarnings(List<String> newWarnings) {
    if (newWarnings != null) {
      warnings.addAll(newWarnings);
    }
  }
}
