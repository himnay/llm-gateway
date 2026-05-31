package com.llm.gateway.llm_gateway.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.llm.gateway.llm_gateway.exception.LLMProviderNotSupportedException;

public enum LlmProvider {

    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    OLLAMA("ollama"),
    GOOGLE("google"),
    HUGGINGFACE("huggingface"),
    COHERE("cohere");

    private final String key;

    LlmProvider(String key) {
        this.key = key;
    }

    @JsonValue
    public String key() {
        return key;
    }

    @JsonCreator
    public static LlmProvider fromKey(String key) {
        for (LlmProvider p : values()) {
            if (p.key.equalsIgnoreCase(key)) {
                return p;
            }
        }
        throw new LLMProviderNotSupportedException("Unknown LLM provider: '" + key + "'. Valid: openai, anthropic, ollama, google, huggingface, cohere");
    }
}
