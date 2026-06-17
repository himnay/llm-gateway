package com.llm.gateway.llm_gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmResponse {

    private String provider;
    private String model;
    private String content;

    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    @JsonProperty("total_tokens")
    private Integer totalTokens;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("span_id")
    private String spanId;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("latency_ms")
    private Long latencyMs;

    private Long timestamp;

    @JsonProperty("cache_hit")
    private Boolean cacheHit;

    private Boolean sanitized;

    private String error;

    /** Echoed from the X-Request-ID request header (or gateway-generated if absent). */
    @JsonProperty("correlation_id")
    private String correlationId;

    /** Citations from {@link LlmRequest#getCitations()}, passed through untouched. */
    private List<Citation> citations;
}

