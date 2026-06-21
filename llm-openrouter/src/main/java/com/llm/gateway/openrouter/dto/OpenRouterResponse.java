package com.llm.gateway.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenRouterResponse {

  private String content;
  private String model;
  private String provider;
  private String error;

  @JsonProperty("prompt_tokens")
  private Integer promptTokens;

  @JsonProperty("completion_tokens")
  private Integer completionTokens;

  @JsonProperty("total_tokens")
  private Integer totalTokens;

  @JsonProperty("latency_ms")
  private Long latencyMs;

  @JsonProperty("correlation_id")
  private String correlationId;

  private Long timestamp;
}
