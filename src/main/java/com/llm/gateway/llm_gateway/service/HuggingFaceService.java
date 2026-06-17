package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.template.PromptTemplateService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HuggingFace Serverless Inference API — OpenAI-compatible chat endpoint.
 * Endpoint: POST https://api-inference.huggingface.co/v1/chat/completions
 * Docs: https://huggingface.co/docs/api-inference/tasks/chat-completion
 *
 * This endpoint is compatible with any model that supports the chat completion
 * task on the HuggingFace Hub (e.g. Mistral, Llama, Qwen, Phi).
 */
@Service
@ConditionalOnProperty(prefix = "llm.providers.huggingface", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HuggingFaceService extends AbstractRestLlmService {

    private static final String PROVIDER = "huggingface";
    private static final String CHAT_ENDPOINT =
            "https://api-inference.huggingface.co/v1/chat/completions";

    @Value("${llm.providers.huggingface.api-key:}")
    private String apiKey;

    @Value("${llm.providers.huggingface.model:mistralai/Mistral-7B-Instruct-v0.1}")
    private String defaultModel;

    public HuggingFaceService(WebClient webClient, ObjectMapper objectMapper,
                              PromptTemplateService promptTemplateService,
                              ObservationRegistry observationRegistry) {
        super(webClient, objectMapper, promptTemplateService, observationRegistry);
    }

    @Override
    public String getProviderName() { return PROVIDER; }

    @Override
    protected String displayName() { return "Hugging Face"; }

    @Override
    protected String defaultModel() { return defaultModel; }

    @Override
    protected String endpoint(String model) { return CHAT_ENDPOINT; }

    @Override
    protected void customizeHeaders(HttpHeaders headers) {
        headers.setBearerAuth(apiKey);
    }

    @Override
    protected Map<String, Object> buildPayload(LlmRequest request, String model, String systemText) {
        // OpenAI-compatible messages format
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemText != null && !systemText.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemText));
        }
        messages.add(Map.of("role", "user", "content", request.getPrompt()));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model",       model);
        payload.put("messages",    messages);
        payload.put("max_tokens",  request.getMaxTokens()   != null ? request.getMaxTokens()   : 512);
        payload.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.7);
        payload.put("stream",      false);
        return payload;
    }

    @Override
    protected LlmResponse.LlmResponseBuilder parseResponse(JsonNode root, String model) {
        String text = root.path("choices").get(0).path("message").path("content").asText();

        JsonNode usage = root.path("usage");
        return LlmResponse.builder()
                .content(text)
                .promptTokens(usage.has("prompt_tokens")         ? usage.path("prompt_tokens").intValue()     : null)
                .completionTokens(usage.has("completion_tokens") ? usage.path("completion_tokens").intValue() : null)
                .totalTokens(usage.has("total_tokens")           ? usage.path("total_tokens").intValue()      : null);
    }
}
