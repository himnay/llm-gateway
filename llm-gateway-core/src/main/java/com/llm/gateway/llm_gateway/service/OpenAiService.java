package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmServiceProvider;
import com.llm.gateway.llm_gateway.guardrail.MetricsAdvisor;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.template.PromptTemplateService;
import com.llm.gateway.llm_gateway.tools.GatewayTools;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@ConditionalOnProperty(
    prefix = "llm.providers.openai",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OpenAiService implements LlmServiceProvider {

  private static final String PROVIDER = "openai";

  private final ChatClient chatClient;
  private final GatewayTools gatewayTools;
  private final PromptTemplateService promptTemplateService;

  @Value("${llm.providers.openai.model:gpt-4o}")
  private String defaultModel;

  public OpenAiService(
      @Qualifier("openAiChatClient") ChatClient chatClient,
      GatewayTools gatewayTools,
      PromptTemplateService promptTemplateService) {
    this.chatClient = chatClient;
    this.gatewayTools = gatewayTools;
    this.promptTemplateService = promptTemplateService;
  }

  @Override
  public String getProviderName() {
    return PROVIDER;
  }

  @Observed(
      name = "llm.provider.execute",
      contextualName = "openai-execute",
      lowCardinalityKeyValues = {"provider", "openai"})
  @Override
  public LlmResponse execute(LlmRequest request) {
    String model = request.getModel() != null ? request.getModel() : defaultModel;
    String systemText = promptTemplateService.renderSystemPrompt(PROVIDER, request);
    String assistantText = promptTemplateService.renderAssistantMessage(request);

    log.info("Invoking OpenAI | model={}", model);
    try {
      var spec = chatClient.prompt().system(systemText).user(request.getPrompt());

      if (assistantText != null) {
        spec = spec.messages(new AssistantMessage(assistantText));
      }

      ChatResponse chatResponse =
          spec.options(
                  OpenAiChatOptions.builder()
                      .model(model)
                      .temperature(
                          request.getTemperature() != null ? request.getTemperature() : 0.7)
                      .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048))
              .advisors(
                  a -> {
                    if (request.getSessionId() != null) {
                      a.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
                    }
                    a.param(MetricsAdvisor.PROVIDER_PARAM, PROVIDER);
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
          .provider("OpenAI")
          .model(model)
          .error(e.getMessage())
          .timestamp(System.currentTimeMillis())
          .build();
    }
  }

  /** Streaming variant — returns token chunks as a Flux. */
  public Flux<String> stream(LlmRequest request) {
    String model = request.getModel() != null ? request.getModel() : defaultModel;
    String systemText = promptTemplateService.renderSystemPrompt(PROVIDER, request);
    return chatClient
        .prompt()
        .system(systemText)
        .user(request.getPrompt())
        .options(
            OpenAiChatOptions.builder()
                .model(model)
                .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7))
        .advisors(
            a -> {
              if (request.getSessionId() != null) {
                a.param(ChatMemory.CONVERSATION_ID, request.getSessionId());
              }
              a.param(MetricsAdvisor.PROVIDER_PARAM, PROVIDER);
            })
        .stream()
        .content();
  }

  /** Structured-output variant — deserialises the response into a typed Java record. */
  public <T> T extractStructured(LlmRequest request, Class<T> responseType) {
    String model = request.getModel() != null ? request.getModel() : defaultModel;
    String systemText = promptTemplateService.renderSystemPrompt(PROVIDER, request);
    return chatClient
        .prompt()
        .system(systemText)
        .user(request.getPrompt())
        .options(OpenAiChatOptions.builder().model(model))
        .call()
        .entity(responseType);
  }
}
