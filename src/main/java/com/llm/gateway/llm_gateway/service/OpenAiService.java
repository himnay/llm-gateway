package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.advisor.MetricsAdvisor;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmServiceProvider;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.tools.GatewayTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class OpenAiService implements LlmServiceProvider {

    private final ChatClient chatClient;
    private final GatewayTools gatewayTools;

    @Value("${llm.providers.openai.model:gpt-4o}")
    private String defaultModel;

    public OpenAiService(
            @Qualifier("openAiChatClient") ChatClient chatClient,
            GatewayTools gatewayTools) {
        this.chatClient   = chatClient;
        this.gatewayTools = gatewayTools;
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        log.info("Invoking OpenAI via Spring AI ChatClient | model={}", model);

        try {
            ChatResponse chatResponse = chatClient.prompt()
                    .system(sp -> {
                        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                            sp.text(request.getSystemPrompt());
                        }
                    })
                    .user(request.getPrompt())
                    .options(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7)
                            .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048))
                    .advisors(spec -> {
                        if (request.getSessionId() != null) {
                            spec.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
                        }
                        spec.param(MetricsAdvisor.PROVIDER_PARAM, "openai");
                    })
                    .tools(gatewayTools)
                    .call()
                    .chatResponse();

            var usage = chatResponse.getMetadata().getUsage();
            return LlmResponse.builder()
                    .provider("OpenAI")
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
            log.error("OpenAI ChatClient error | model={}", model, e);
            return LlmResponse.builder()
                    .provider("OpenAI").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * Streaming variant – returns token chunks as a Flux.
     * Controller maps this to text/event-stream (SSE).
     */
    public Flux<String> stream(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        return chatClient.prompt()
                .system(sp -> {
                    if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                        sp.text(request.getSystemPrompt());
                    }
                })
                .user(request.getPrompt())
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7))
                .advisors(spec -> {
                    if (request.getSessionId() != null) {
                        spec.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
                    }
                    spec.param(MetricsAdvisor.PROVIDER_PARAM, "openai");
                })
                .stream()
                .content();
    }

    /**
     * Structured-output variant – deserialises the model response into a typed Java record.
     * The model is instructed via the response schema; no manual JSON parsing needed.
     */
    public <T> T extractStructured(LlmRequest request, Class<T> responseType) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        return chatClient.prompt()
                .system(sp -> {
                    if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                        sp.text(request.getSystemPrompt());
                    }
                })
                .user(request.getPrompt())
                .options(OpenAiChatOptions.builder().model(model))
                .call()
                .entity(responseType);
    }
}
