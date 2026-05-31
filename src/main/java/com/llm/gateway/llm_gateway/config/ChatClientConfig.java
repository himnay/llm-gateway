package com.llm.gateway.llm_gateway.config;

import com.llm.gateway.llm_gateway.advisor.MetricsAdvisor;
import com.llm.gateway.llm_gateway.advisor.PromptGuardAdvisor;
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
 * Creates a named ChatClient bean per provider, each wired with the same advisor chain:
 *
 *   PromptGuardAdvisor   → blocks injections (highest precedence)
 *   MetricsAdvisor       → records latency and token-usage metrics
 *   MessageChatMemoryAdvisor → injects conversation history from ChatMemory
 *   SimpleLoggerAdvisor  → DEBUG-level request/response logging (lowest precedence)
 *
 * Services inject the appropriate client via @Qualifier.
 */
@Configuration
@RequiredArgsConstructor
public class ChatClientConfig {

    private final PromptGuardAdvisor promptGuard;
    private final MetricsAdvisor metrics;
    private final ChatMemory chatMemory;

    @Primary
    @Bean("openAiChatClient")
    public ChatClient openAiChatClient(OpenAiChatModel model) {

        return ChatClient.builder(model)
                .defaultAdvisors(
                        promptGuard,
                        metrics,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .build();
    }

    @Bean("anthropicChatClient")
    public ChatClient anthropicChatClient(AnthropicChatModel model) {

        return ChatClient.builder(model)
                .defaultAdvisors(
                        promptGuard,
                        metrics,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .build();
    }

    @Bean("ollamaChatClient")
    public ChatClient ollamaChatClient(OllamaChatModel model) {
        return ChatClient.builder(model)
                .defaultAdvisors(
                        promptGuard,
                        metrics,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .build();
    }


}
