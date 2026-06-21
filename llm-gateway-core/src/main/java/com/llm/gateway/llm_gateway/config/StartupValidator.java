package com.llm.gateway.llm_gateway.config;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Emits startup warnings when enabled LLM providers have missing or placeholder API keys. Does not
 * fail startup — providers without keys will error at request time instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator {

  private static final String PLACEHOLDER_PREFIX = "sk-placeholder";

  private final LlmProviderProperties providerProperties;

  @EventListener(ApplicationStartedEvent.class)
  public void validateProviderKeys() {
    if (providerProperties.getProviders() == null) return;

    List<String> warnings = new ArrayList<>();

    providerProperties
        .getProviders()
        .forEach(
            (provider, config) -> {
              if (!config.isEnabled()) return;

              String key = config.getApiKey();
              if (!StringUtils.hasText(key)) {
                warnings.add(
                    String.format(
                        "Provider '%s' is enabled but has no API key configured", provider));
              } else if (key.startsWith(PLACEHOLDER_PREFIX)) {
                warnings.add(
                    String.format(
                        "Provider '%s' is using a placeholder API key — real requests will fail",
                        provider));
              }
            });

    if (!warnings.isEmpty()) {
      log.warn("=== STARTUP VALIDATION WARNINGS ===");
      warnings.forEach(w -> log.warn("  [WARN] {}", w));
      log.warn("===================================");
    } else {
      log.info("Startup validation: all enabled providers have API keys configured");
    }
  }
}
