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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cohere Command R integration using the v2 chat API.
 * Endpoint: POST https://api.cohere.com/v2/chat
 * Docs: https://docs.cohere.com/reference/chat
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "llm.providers.cohere", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CohereService implements LlmServiceProvider {

    private static final String PROVIDER = "cohere";
    private static final String API_BASE = "https://api.cohere.com/v2/chat";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;

    @Value("${llm.providers.cohere.api-key:}")
    private String apiKey;

    @Value("${llm.providers.cohere.model:command-r-plus}")
    private String defaultModel;

    @Override
    public String getProviderName() { return PROVIDER; }

    @Override
    public LlmResponse execute(LlmRequest request) {
        String model      = request.getModel() != null ? request.getModel() : defaultModel;
        String systemText = promptTemplateService.renderSystemPrompt(PROVIDER, request);

        log.info("Invoking Cohere v2 API | model={}", model);

        try {
            // Build messages array — system + user
            List<Map<String, Object>> messages = new ArrayList<>();
            if (systemText != null && !systemText.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemText));
            }
            messages.add(Map.of("role", "user", "content", request.getPrompt()));

            Map<String, Object> payload = new HashMap<>();
            payload.put("model",      model);
            payload.put("messages",   messages);
            payload.put("max_tokens", request.getMaxTokens()   != null ? request.getMaxTokens()   : 1024);
            payload.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.7);

            String raw = webClient.post()
                    .uri(API_BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Client-Name", "llm-gateway")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root    = objectMapper.readTree(raw);
            JsonNode content = root.path("message").path("content");
            String   text    = content.isArray() && content.size() > 0
                    ? content.get(0).path("text").asText()
                    : root.path("text").asText();  // fallback for v1 shape

            JsonNode usage = root.path("usage").path("billed_units");
            return LlmResponse.builder()
                    .provider("Cohere").model(model)
                    .content(text)
                    .promptTokens(usage.has("input_tokens")  ? usage.path("input_tokens").intValue()  : null)
                    .completionTokens(usage.has("output_tokens") ? usage.path("output_tokens").intValue() : null)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("Cohere API error | model={}", model, e);
            return LlmResponse.builder().provider("Cohere").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis()).build();
        }
    }
}
