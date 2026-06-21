package com.llm.gateway.llm_gateway.config;

import com.llm.gateway.llm_gateway.guardrail.HallucinationMonitorAdvisor;
import com.llm.gateway.llm_gateway.guardrail.MetricsAdvisor;
import com.llm.gateway.llm_gateway.guardrail.PiiRedactionAdvisor;
import com.llm.gateway.llm_gateway.guardrail.ResponseFormatAdvisor;
import com.llm.gateway.llm_gateway.guardrail.TopicFilterAdvisor;
import com.llm.gateway.llm_gateway.guardrail.ToxicityFilterAdvisor;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the full guardrail chain into every ChatClient bean.
 *
 * <p>Execution order (lower order = runs first on input, last on output):
 *
 * <p>INPUT guardrails (before LLM call). Prompt injection detection/sanitization runs upstream of
 * this chain, in LlmGatewayFacade, before the cache lookup: 1. ToxicityFilterAdvisor
 * HIGHEST_PRECEDENCE + 1 – harmful content block 2. PiiRedactionAdvisor HIGHEST_PRECEDENCE + 2 –
 * PII redaction 3. TopicFilterAdvisor HIGHEST_PRECEDENCE + 3 – configurable topic restriction 4.
 * MetricsAdvisor HIGHEST_PRECEDENCE + 4 – latency + token metrics wrapper 5.
 * MessageChatMemoryAdvisor (memory injection) 6. SimpleLoggerAdvisor (debug logging)
 *
 * <p>OUTPUT guardrails (after LLM call, async – zero latency impact): 7. ResponseFormatAdvisor
 * LOWEST_PRECEDENCE - 20 – length + refusal + truncation check 8. HallucinationMonitorAdvisor
 * LOWEST_PRECEDENCE - 10 – hallucination scoring
 */
@Configuration
@RequiredArgsConstructor
public class ChatClientConfig {

  private final ToxicityFilterAdvisor toxicityFilter;
  private final PiiRedactionAdvisor piiRedaction;
  private final TopicFilterAdvisor topicFilter;
  private final MetricsAdvisor metrics;
  private final ResponseFormatAdvisor responseFormat;
  private final HallucinationMonitorAdvisor hallucinationMonitor;
  private final ChatMemory chatMemory;

  @Primary
  @Bean("openAiChatClient")
  public ChatClient openAiChatClient(OpenAiChatModel model) {
    return ChatClient.builder(model)
        .defaultAdvisors(
            toxicityFilter,
            piiRedaction,
            topicFilter,
            metrics,
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            new SimpleLoggerAdvisor(),
            responseFormat,
            hallucinationMonitor)
        .build();
  }

  @Bean("anthropicChatClient")
  public ChatClient anthropicChatClient(AnthropicChatModel model) {
    return ChatClient.builder(model)
        .defaultAdvisors(
            toxicityFilter,
            piiRedaction,
            topicFilter,
            metrics,
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            new SimpleLoggerAdvisor(),
            responseFormat,
            hallucinationMonitor)
        .build();
  }

  @Bean("ollamaChatClient")
  public ChatClient ollamaChatClient(OllamaChatModel model) {
    return ChatClient.builder(model)
        .defaultAdvisors(
            toxicityFilter,
            piiRedaction,
            topicFilter,
            metrics,
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            new SimpleLoggerAdvisor(),
            responseFormat,
            hallucinationMonitor)
        .build();
  }
}
