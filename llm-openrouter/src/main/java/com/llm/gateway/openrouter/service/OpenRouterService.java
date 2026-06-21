package com.llm.gateway.openrouter.service;

import com.llm.gateway.openrouter.dto.OpenRouterRequest;
import com.llm.gateway.openrouter.dto.OpenRouterResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper over OpenRouter (via Spring AI's OpenAI-compatible {@link ChatClient}) — resolves a
 * per-request model override, calls OpenRouter, and maps the result to {@link OpenRouterResponse}.
 * Wrapped in Resilience4j retry/circuit-breaker since OpenRouter is a third-party dependency that
 * can rate-limit or time out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenRouterService {

  private final ChatClient openRouterChatClient;

  @Retry(name = "openrouter", fallbackMethod = "retryFallback")
  @CircuitBreaker(name = "openrouter", fallbackMethod = "circuitBreakerFallback")
  public OpenRouterResponse chat(OpenRouterRequest request) {
    long startTime = System.currentTimeMillis();
    log.info(
        "OPENROUTER | calling | model={} | cid={}", request.getModel(), request.getCorrelationId());

    ChatClient.ChatClientRequestSpec spec = openRouterChatClient.prompt().user(request.getPrompt());
    if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
      spec = spec.system(request.getSystemPrompt());
    }
    if (request.getModel() != null && !request.getModel().isBlank()) {
      spec = spec.options(OpenAiChatOptions.builder().model(request.getModel()));
    }

    ChatResponse response = spec.call().chatResponse();
    String content = response != null ? response.getResult().getOutput().getText() : null;

    var usage = response != null ? response.getMetadata().getUsage() : null;
    var metadataModel =
        response != null && response.getMetadata().getModel() != null
            ? response.getMetadata().getModel()
            : request.getModel();

    return OpenRouterResponse.builder()
        .content(content)
        .model(metadataModel)
        .provider("openrouter")
        .promptTokens(usage != null ? usage.getPromptTokens() : null)
        .completionTokens(usage != null ? usage.getCompletionTokens() : null)
        .totalTokens(usage != null ? usage.getTotalTokens() : null)
        .latencyMs(System.currentTimeMillis() - startTime)
        .correlationId(request.getCorrelationId())
        .timestamp(System.currentTimeMillis())
        .build();
  }

  public OpenRouterResponse circuitBreakerFallback(OpenRouterRequest request, Throwable ex) {
    log.warn("OPENROUTER | CIRCUIT_OPEN | {}", ex.getMessage());
    return errorResponse(
        request, "OpenRouter temporarily unavailable (circuit open). Please retry later.");
  }

  public OpenRouterResponse retryFallback(OpenRouterRequest request, Throwable ex) {
    log.error("OPENROUTER | RETRY_EXHAUSTED | {}", ex.getMessage());
    return errorResponse(request, "OpenRouter failed after multiple retries: " + ex.getMessage());
  }

  private OpenRouterResponse errorResponse(OpenRouterRequest request, String message) {
    return OpenRouterResponse.builder()
        .provider("openrouter")
        .error(message)
        .correlationId(request.getCorrelationId())
        .timestamp(System.currentTimeMillis())
        .build();
  }
}
