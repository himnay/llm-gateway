package com.llm.gateway.llm_gateway.facade;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;

/**
 * Facade SPI – every LLM provider service must implement this interface.
 *
 * <p>Adding a new provider is as simple as:
 *
 * <ol>
 *   <li>Create a {@code @Service} class that implements this interface.
 *   <li>Return the provider's canonical name from {@link #getProviderName()}.
 *   <li>Implement the call in {@link #execute(LlmRequest)}.
 * </ol>
 *
 * Spring will auto-discover the bean and register it in the gateway.
 */
public interface LlmServiceProvider {

  /**
   * Canonical, lower-case name used for routing. Examples: {@code "openai"}, {@code "google"},
   * {@code "anthropic"}
   */
  String getProviderName();

  /**
   * Execute the LLM request and return a structured response. Implementations must <b>never</b>
   * throw – errors must be captured inside the returned {@link LlmResponse#getError()} field.
   */
  LlmResponse execute(LlmRequest request);
}
