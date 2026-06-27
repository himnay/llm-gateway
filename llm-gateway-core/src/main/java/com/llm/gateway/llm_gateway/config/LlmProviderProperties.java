package com.llm.gateway.llm_gateway.config;

import com.llm.gateway.llm_gateway.dto.LlmProvider;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProviderProperties {

  private Map<LlmProvider, ProviderConfig> providers;

  @Data
  public static class ProviderConfig {
    private boolean enabled;
    @lombok.ToString.Exclude
    private String apiKey;
    private String apiUrl;
    private String model;
  }
}
