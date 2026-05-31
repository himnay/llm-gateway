package com.llm.gateway.llm_gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "llm.providers")
public class LlmProviderProperties {

    private Map<String, ProviderConfig> providers;

    @Data
    public static class ProviderConfig {
        private boolean enabled;
        private String apiKey;
        private String apiUrl;
        private String model;
    }
}

