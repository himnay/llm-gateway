package com.llm.gateway.openrouter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.llm.gateway.openrouter.dto.OpenRouterRequest;
import com.llm.gateway.openrouter.dto.OpenRouterResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class OpenRouterServiceTest {

  private final ChatClient chatClient = mock(ChatClient.class);
  private final ChatClient.ChatClientRequestSpec requestSpec =
      mock(ChatClient.ChatClientRequestSpec.class);
  private final ChatClient.CallResponseSpec callResponseSpec =
      mock(ChatClient.CallResponseSpec.class);
  private final OpenRouterService service = new OpenRouterService(chatClient);

  @Test
  @DisplayName("chat() maps a successful ChatResponse to OpenRouterResponse")
  void chatMapsSuccessfulResponse() {
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(10);
    when(usage.getCompletionTokens()).thenReturn(5);
    when(usage.getTotalTokens()).thenReturn(15);

    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().model("openai/gpt-4o").usage(usage).build();

    AssistantMessage assistantMessage = new AssistantMessage("Hello from OpenRouter");
    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(java.util.List.of(generation), metadata);
    when(callResponseSpec.chatResponse()).thenReturn(chatResponse);

    OpenRouterRequest request =
        OpenRouterRequest.builder().prompt("Hi").correlationId("cid-1").build();

    OpenRouterResponse response = service.chat(request);

    assertThat(response.getContent()).isEqualTo("Hello from OpenRouter");
    assertThat(response.getModel()).isEqualTo("openai/gpt-4o");
    assertThat(response.getProvider()).isEqualTo("openrouter");
    assertThat(response.getPromptTokens()).isEqualTo(10);
    assertThat(response.getCompletionTokens()).isEqualTo(5);
    assertThat(response.getTotalTokens()).isEqualTo(15);
    assertThat(response.getCorrelationId()).isEqualTo("cid-1");
    assertThat(response.getError()).isNull();
  }

  @Test
  @DisplayName("chat() applies system prompt and model override when present")
  void chatAppliesSystemPromptAndModelOverride() {
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
    when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().model("anthropic/claude-3.5-sonnet").build();
    AssistantMessage assistantMessage = new AssistantMessage("ok");
    ChatResponse chatResponse =
        new ChatResponse(java.util.List.of(new Generation(assistantMessage)), metadata);
    when(callResponseSpec.chatResponse()).thenReturn(chatResponse);

    OpenRouterRequest request =
        OpenRouterRequest.builder()
            .prompt("Hi")
            .systemPrompt("You are terse.")
            .model("anthropic/claude-3.5-sonnet")
            .correlationId("cid-2")
            .build();

    OpenRouterResponse response = service.chat(request);

    assertThat(response.getContent()).isEqualTo("ok");
    assertThat(response.getModel()).isEqualTo("anthropic/claude-3.5-sonnet");
  }

  @Test
  @DisplayName("circuitBreakerFallback() returns a structured error response")
  void circuitBreakerFallbackReturnsErrorResponse() {
    OpenRouterRequest request =
        OpenRouterRequest.builder().prompt("Hi").correlationId("cid-3").build();

    OpenRouterResponse response =
        service.circuitBreakerFallback(request, new RuntimeException("boom"));

    assertThat(response.getError()).contains("circuit open");
    assertThat(response.getProvider()).isEqualTo("openrouter");
    assertThat(response.getCorrelationId()).isEqualTo("cid-3");
  }

  @Test
  @DisplayName("retryFallback() returns a structured error response")
  void retryFallbackReturnsErrorResponse() {
    OpenRouterRequest request =
        OpenRouterRequest.builder().prompt("Hi").correlationId("cid-4").build();

    OpenRouterResponse response = service.retryFallback(request, new RuntimeException("boom"));

    assertThat(response.getError()).contains("multiple retries");
    assertThat(response.getCorrelationId()).isEqualTo("cid-4");
  }
}
