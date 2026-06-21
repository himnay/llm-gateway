package com.llm.gateway.llm_gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Image-generation request for {@code POST /llm/image}. Backed by the OpenAI image model (DALL·E)
 * auto-configured by the Spring AI OpenAI starter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationRequest {

  /** The text prompt describing the image to generate. */
  private String prompt;

  /** Image model, e.g. {@code dall-e-3} (default) or {@code dall-e-2}. */
  private String model;

  /** Output size, e.g. {@code 1024x1024} (default), {@code 1024x1792}, {@code 1792x1024}. */
  private String size;

  /** Number of images to generate (dall-e-3 supports 1). */
  private Integer n;

  /** {@code b64_json} (default) or {@code url}. */
  @JsonProperty("response_format")
  private String responseFormat;

  /**
   * Correlation ID propagated from the X-Request-ID header. Set by the handler; not expected from
   * the client body.
   */
  @JsonProperty(value = "correlation_id", access = JsonProperty.Access.READ_ONLY)
  private String correlationId;
}
