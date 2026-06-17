package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.guardrail.MetricsAdvisor;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmServiceProvider;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.template.PromptTemplateService;
import com.llm.gateway.llm_gateway.tools.GatewayTools;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "llm.providers.ollama", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OllamaService implements LlmServiceProvider {

    private static final String PROVIDER = "ollama";

    private final ChatClient chatClient;
    private final GatewayTools gatewayTools;
    private final PromptTemplateService promptTemplateService;

    @Value("${llm.providers.ollama.model:llama3.1}")
    private String defaultModel;

    public OllamaService(
            @Qualifier("ollamaChatClient") ChatClient chatClient,
            GatewayTools gatewayTools,
            PromptTemplateService promptTemplateService) {
        this.chatClient            = chatClient;
        this.gatewayTools          = gatewayTools;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public String getProviderName() { return PROVIDER; }

    @Observed(name = "llm.provider.execute", contextualName = "ollama-execute",
              lowCardinalityKeyValues = {"provider", "ollama"})
    @Override
    public LlmResponse execute(LlmRequest request) {
        String model         = request.getModel() != null ? request.getModel() : defaultModel;
        String systemText    = promptTemplateService.renderSystemPrompt(PROVIDER, request);
        String assistantText = promptTemplateService.renderAssistantMessage(request);

        log.info("Invoking Ollama | model={}", model);
        try {
            var spec = chatClient.prompt()
                    .system(systemText)
                    .user(request.getPrompt());

            if (assistantText != null) {
                spec = spec.messages(new AssistantMessage(assistantText));
            }

            ChatResponse chatResponse = spec
                    .options(OllamaChatOptions.builder()
                            .model(model)
                            .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7))
                    .advisors(a -> {
                        if (request.getSessionId() != null) {
                            a.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
                        }
                        a.param(MetricsAdvisor.PROVIDER_PARAM, PROVIDER);
                    })
                    .tools(gatewayTools)
                    .call()
                    .chatResponse();

            return LlmResponse.builder()
                    .provider("Ollama").model(model)
                    .content(chatResponse.getResult().getOutput().getText())
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (PromptValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ollama ChatClient error | model={}", model, e);
            return LlmResponse.builder().provider("Ollama").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis()).build();
        }
    }

    public Flux<String> stream(LlmRequest request) {
        String model      = request.getModel() != null ? request.getModel() : defaultModel;
        String systemText = promptTemplateService.renderSystemPrompt(PROVIDER, request);
        return chatClient.prompt()
                .system(systemText)
                .user(request.getPrompt())
                .options(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7))
                .advisors(a -> {
                    if (request.getSessionId() != null) {
                        a.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
                    }
                    a.param(MetricsAdvisor.PROVIDER_PARAM, PROVIDER);
                })
                .tools(gatewayTools)
                .stream().content();
    }
}
