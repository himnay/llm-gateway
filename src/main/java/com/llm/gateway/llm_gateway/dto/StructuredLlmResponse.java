package com.llm.gateway.llm_gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StructuredLlmResponse(
    @JsonProperty("summary") String summary,
    @JsonProperty("key_points") java.util.List<String> keyPoints,
    @JsonProperty("sentiment") String sentiment,
    @JsonProperty("confidence") double confidence) {}
