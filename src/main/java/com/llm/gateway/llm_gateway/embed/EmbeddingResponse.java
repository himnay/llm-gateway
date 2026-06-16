package com.llm.gateway.llm_gateway.embed;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbeddingResponse {
    private String provider;
    private String model;
    private List<Double> embedding;
    private int dimensions;
    @JsonProperty("request_id")
    private String requestId;
    private Long timestamp;
    private String error;
}
