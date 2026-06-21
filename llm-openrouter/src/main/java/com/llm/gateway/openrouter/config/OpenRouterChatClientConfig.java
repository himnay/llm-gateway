package com.llm.gateway.openrouter.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI auto-configures an {@link OpenAiChatModel} bean from {@code spring.ai.openai.*}
 * (base-url overridden to OpenRouter's OpenAI-SDK-compatible endpoint — see application.yaml). This
 * just wraps it in a {@link ChatClient} with the same logging advisor used elsewhere in the
 * platform.
 */
@Configuration
public class OpenRouterChatClientConfig {

  @Bean
  public ChatClient openRouterChatClient(OpenAiChatModel model) {
    return ChatClient.builder(model).defaultAdvisors(new SimpleLoggerAdvisor()).build();
  }
}
