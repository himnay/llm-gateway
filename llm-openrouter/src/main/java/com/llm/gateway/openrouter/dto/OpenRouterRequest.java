package com.llm.gateway.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenRouterRequest {

  @NotBlank(message = "'prompt' is required and must not be blank")
  @Size(max = 10_000, message = "'prompt' exceeds the maximum allowed length of 10,000 characters")
  private String prompt;

  /** Overrides the configured default model, e.g. "anthropic/claude-3.5-sonnet". */
  private String model;

  @JsonProperty("system_prompt")
  private String systemPrompt;

  /**
   * Correlation ID propagated from the X-Request-ID header. Set by the handler; not expected from
   * the client body.
   */
  @JsonProperty(value = "correlation_id", access = JsonProperty.Access.READ_ONLY)
  private String correlationId;
}
