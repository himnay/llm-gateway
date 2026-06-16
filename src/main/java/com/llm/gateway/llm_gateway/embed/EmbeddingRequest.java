package com.llm.gateway.llm_gateway.embed;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class EmbeddingRequest {
    private String text;
    private String model;
    @JsonProperty("session_id")
    private String sessionId;
}
