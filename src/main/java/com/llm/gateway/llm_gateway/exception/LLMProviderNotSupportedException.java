package com.llm.gateway.llm_gateway.exception;

import java.util.Set;

public class LLMProviderNotSupportedException extends RuntimeException {

    private final String provider;

    public LLMProviderNotSupportedException(String provider, Set<String> registered) {
        super("Unknown LLM provider: '" + provider + "'. Registered: " + registered);
        this.provider = provider;
    }

    public LLMProviderNotSupportedException(String message) {
        super(message);
        this.provider = null;
    }

    public String getProvider() {
        return provider;
    }
}
