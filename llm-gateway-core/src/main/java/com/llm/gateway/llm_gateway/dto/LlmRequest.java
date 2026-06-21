package com.llm.gateway.llm_gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

  private String prompt;

  private LlmProvider provider;

  private String model;

  @JsonProperty("system_prompt")
  private String systemPrompt;

  @JsonProperty("max_tokens")
  private Integer maxTokens;

  private Double temperature;

  @JsonProperty("top_p")
  private Double topP;

  @JsonProperty("stream")
  private Boolean stream;

  @JsonProperty("session_id")
  private String sessionId;

  @JsonProperty("providers")
  private List<LlmProvider> providers;

  /** Variables injected into the per-provider .st system-prompt template. */
  @JsonProperty("template_vars")
  private Map<String, Object> templateVars;

  /**
   * Optional assistant message prepended to the conversation turn. Useful for prefilling
   * (Anthropic), few-shot priming, or format anchoring.
   */
  @JsonProperty("assistant_message")
  private String assistantMessage;

  /**
   * Correlation ID propagated from the X-Request-ID header. Set by the handler; not expected from
   * the client body.
   */
  @JsonProperty(value = "correlation_id", access = JsonProperty.Access.READ_ONLY)
  private String correlationId;

  /**
   * Optional citations from an upstream RAG call (e.g. llm-rag), supplied by the caller so the
   * gateway can echo them back on {@link LlmResponse} untouched alongside trace/audit metadata.
   */
  private List<Citation> citations;
}
