package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmServiceProvider;
import com.llm.gateway.llm_gateway.template.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
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
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "llm.providers.google", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoogleGeminiService implements LlmServiceProvider {

    private static final String PROVIDER = "google";
    private static final String API_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;

    @Value("${llm.providers.google.api-key:}")
    private String apiKey;

    @Value("${llm.providers.google.model:gemini-1.5-pro-latest}")
    private String defaultModel;

    @Override
    public String getProviderName() { return PROVIDER; }

    @Override
    public LlmResponse execute(LlmRequest request) {
        String model      = request.getModel() != null ? request.getModel() : defaultModel;
        String systemText = promptTemplateService.renderSystemPrompt(PROVIDER, request);

        log.info("Invoking Google Gemini API | model={}", model);

        try {
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

            String url = API_BASE + model + ":generateContent?key=" + apiKey;

            String raw = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(raw);
            String   text = root.path("candidates").get(0)
                    .path("content").path("parts").get(0).path("text").asText();

            JsonNode usage = root.path("usageMetadata");
            return LlmResponse.builder()
                    .provider("Google Gemini").model(model)
                    .content(text)
                    .promptTokens(usage.has("promptTokenCount")     ? usage.path("promptTokenCount").intValue()     : null)
                    .completionTokens(usage.has("candidatesTokenCount") ? usage.path("candidatesTokenCount").intValue() : null)
                    .totalTokens(usage.has("totalTokenCount")       ? usage.path("totalTokenCount").intValue()       : null)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("Google Gemini API error | model={}", model, e);
            return LlmResponse.builder().provider("Google Gemini").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis()).build();
        }
    }
}
