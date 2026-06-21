package com.llm.gateway.llm_gateway.facade;

import com.llm.gateway.llm_gateway.exception.LLMProviderNotSupportedException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Provider registry (GoF <b>Factory Method</b> / Registry) — the single place where {@link
 * LlmServiceProvider} <b>Strategy</b> implementations are discovered and resolved by name.
 *
 * <p>Spring auto-discovers every {@code @Service} implementing {@link LlmServiceProvider}; adding a
 * provider requires no change here or in the facade (Open/Closed Principle).
 */
@Slf4j
@Component
public class LlmProviderRegistry {

  private final Map<String, LlmServiceProvider> providers;

  public LlmProviderRegistry(List<LlmServiceProvider> providerList) {
    this.providers =
        providerList.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    p -> p.getProviderName().toLowerCase(), Function.identity()));
  }

  @PostConstruct
  void logRegisteredProviders() {
    log.info(
        "LLM provider registry initialised with {} providers: {}",
        providers.size(),
        providers.keySet());
  }

  /**
   * @throws LLMProviderNotSupportedException when no provider is registered under {@code name}
   *     (mapped to HTTP 400)
   */
  public LlmServiceProvider resolve(String name) {
    LlmServiceProvider provider = providers.get(name.toLowerCase());
    if (provider == null) {
      throw new LLMProviderNotSupportedException(name, providers.keySet());
    }
    return provider;
  }

  public boolean contains(String name) {
    return providers.containsKey(name.toLowerCase());
  }

  public Set<String> names() {
    return providers.keySet();
  }
}
