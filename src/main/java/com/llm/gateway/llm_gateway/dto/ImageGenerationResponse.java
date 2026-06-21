package com.llm.gateway.llm_gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for {@code POST /llm/image}. {@link #images} carries either base64-encoded PNGs ({@code
 * response_format=b64_json}) or hosted URLs ({@code response_format=url}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageGenerationResponse {

  private String model;

  @JsonProperty("response_format")
  private String responseFormat;

  /** Base64 PNGs or URLs, depending on {@link #responseFormat}. */
  private List<String> images;

  private Integer count;

  @JsonProperty("latency_ms")
  private Long latencyMs;

  private Long timestamp;

  private String error;

  @JsonProperty("correlation_id")
  private String correlationId;
}
