package com.llm.gateway.llm_gateway.exception;

public class LLMProviderNotSupportedException extends RuntimeException {

    private final String provider;

    public LLMProviderNotSupportedException(String provider) {
        this.provider = provider;
    }
}
