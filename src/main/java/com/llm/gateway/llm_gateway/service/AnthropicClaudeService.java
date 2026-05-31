package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.guardrail.MetricsAdvisor;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmServiceProvider;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class AnthropicClaudeService implements LlmServiceProvider {

    private final ChatClient chatClient;

    @Value("${llm.providers.anthropic.model:claude-3-5-sonnet-20241022}")
    private String defaultModel;

    public AnthropicClaudeService(@Qualifier("anthropicChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        log.info("Invoking Anthropic Claude via Spring AI ChatClient | model={}", model);

        try {
            ChatResponse chatResponse = chatClient.prompt()
                    .system(sp -> {
                        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                            sp.text(request.getSystemPrompt());
                        }
                    })
                    .user(request.getPrompt())
                    .options(AnthropicChatOptions.builder()
                            .model(model)
                            .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7)
                            .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048))
                    .advisors(spec -> {
                        if (request.getSessionId() != null) {
                            spec.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
                        }
                        spec.param(MetricsAdvisor.PROVIDER_PARAM, "anthropic");
                    })
                    .call()
                    .chatResponse();

            var usage = chatResponse.getMetadata().getUsage();
            return LlmResponse.builder()
                    .provider("Anthropic Claude")
                    .model(model)
                    .content(chatResponse.getResult().getOutput().getText())
                    .completionTokens(usage != null ? usage.getCompletionTokens() : null)
                    .promptTokens(usage != null ? usage.getPromptTokens() : null)
                    .totalTokens(usage != null ? usage.getTotalTokens() : null)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (PromptValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Anthropic ChatClient error | model={}", model, e);
            return LlmResponse.builder()
                    .provider("Anthropic Claude").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    public Flux<String> stream(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        return chatClient.prompt()
                .system(sp -> {
                    if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                        sp.text(request.getSystemPrompt());
                    }
                })
                .user(request.getPrompt())
                .options(AnthropicChatOptions.builder()
                        .model(model)
                        .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7))
                .advisors(spec -> {
                    if (request.getSessionId() != null) {
                        spec.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
                    }
                    spec.param(MetricsAdvisor.PROVIDER_PARAM, "anthropic");
                })
                .stream()
                .content();
    }
}
