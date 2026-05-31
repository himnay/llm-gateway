package com.llm.gateway.llm_gateway.dto;

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
}

