package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.template.PromptTemplateService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini integration via the Generative Language REST API (v1beta).
 * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * Docs: https://ai.google.dev/api/generate-content
 */
@Service
@ConditionalOnProperty(prefix = "llm.providers.google", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoogleGeminiService extends AbstractRestLlmService {

    private static final String PROVIDER = "google";
    private static final String API_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    @Value("${llm.providers.google.api-key:}")
    private String apiKey;

    @Value("${llm.providers.google.model:gemini-1.5-pro-latest}")
    private String defaultModel;

    public GoogleGeminiService(WebClient webClient, ObjectMapper objectMapper,
                               PromptTemplateService promptTemplateService,
                               ObservationRegistry observationRegistry) {
        super(webClient, objectMapper, promptTemplateService, observationRegistry);
    }

    @Override
    public String getProviderName() { return PROVIDER; }

    @Override
    protected String displayName() { return "Google Gemini"; }

    @Override
    protected String defaultModel() { return defaultModel; }

    @Override
    protected String endpoint(String model) {
        return API_BASE + model + ":generateContent?key=" + apiKey;
    }

    @Override
    protected Map<String, Object> buildPayload(LlmRequest request, String model, String systemText) {
        Map<String, Object> payload = new HashMap<>();

        // System instruction — dedicated field in Gemini API (cleaner than fake user/model turns)
        if (systemText != null && !systemText.isBlank()) {
            payload.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemText))));
        }

        payload.put("contents", List.of(Map.of(
                "role",  "user",
                "parts", List.of(Map.of("text", request.getPrompt())))));

        payload.put("generationConfig", Map.of(
                "temperature",     request.getTemperature() != null ? request.getTemperature() : 0.7,
                "topP",            request.getTopP()         != null ? request.getTopP()         : 0.95,
                "maxOutputTokens", request.getMaxTokens()    != null ? request.getMaxTokens()    : 1024));
        return payload;
    }

    @Override
    protected LlmResponse.LlmResponseBuilder parseResponse(JsonNode root, String model) {
        String text = root.path("candidates").get(0)
                .path("content").path("parts").get(0).path("text").asText();

        JsonNode usage = root.path("usageMetadata");
        return LlmResponse.builder()
                .content(text)
                .promptTokens(usage.has("promptTokenCount")         ? usage.path("promptTokenCount").intValue()     : null)
                .completionTokens(usage.has("candidatesTokenCount") ? usage.path("candidatesTokenCount").intValue() : null)
                .totalTokens(usage.has("totalTokenCount")           ? usage.path("totalTokenCount").intValue()      : null);
    }
}
