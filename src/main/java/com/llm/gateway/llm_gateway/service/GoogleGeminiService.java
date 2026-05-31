package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmServiceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleGeminiService implements LlmServiceProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.providers.google.api-key}")
    private String apiKey;

    @Value("${llm.providers.google.model:gemini-1.5-pro-latest}")
    private String defaultModel;

    @Override
    public String getProviderName() { return "google"; }

    @Override
    public LlmResponse execute(LlmRequest request) { return generateContent(request); }

    public LlmResponse generateContent(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        try {
            log.info("Invoking Google Gemini API | model={}", model);

            List<Map<String, Object>> contentsList = new ArrayList<>();
            // System prompt as a "user" turn followed by model acknowledgement (Gemini pattern)
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                contentsList.add(Map.of("role", "user",
                        "parts", List.of(Map.of("text", request.getSystemPrompt()))));
                contentsList.add(Map.of("role", "model",
                        "parts", List.of(Map.of("text", "Understood."))));
            }
            contentsList.add(Map.of("role", "user",
                    "parts", List.of(Map.of("text", request.getPrompt()))));

            Map<String, Object> payload = new HashMap<>();
            payload.put("contents", contentsList);
            payload.put("generationConfig", Map.of(
                    "temperature",      request.getTemperature() != null ? request.getTemperature() : 0.7,
                    "topP",             request.getTopP()         != null ? request.getTopP()         : 0.95,
                    "maxOutputTokens",  request.getMaxTokens()    != null ? request.getMaxTokens()    : 1024
            ));

            String raw = webClient.post()
                    .uri("https://generativelanguage.googleapis.com/v1beta/models/"
                            + model + ":generateContent?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode node = objectMapper.readTree(raw);
            return LlmResponse.builder()
                    .provider("Google Gemini").model(model)
                    .content(node.path("candidates").get(0)
                            .path("content").path("parts").get(0).path("text").asText())
                    .response(raw)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("Google Gemini API error | model={}", model, e);
            return LlmResponse.builder()
                    .provider("Google Gemini").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis())
                    .build();
        }
    }
}
