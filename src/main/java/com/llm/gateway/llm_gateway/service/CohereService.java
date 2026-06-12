package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.template.PromptTemplateService;
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
 * Cohere Command R integration using the v2 chat API.
 * Endpoint: POST https://api.cohere.com/v2/chat
 * Docs: https://docs.cohere.com/reference/chat
 */
@Service
@ConditionalOnProperty(prefix = "llm.providers.cohere", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CohereService extends AbstractRestLlmService {

    private static final String PROVIDER = "cohere";
    private static final String API_BASE = "https://api.cohere.com/v2/chat";

    @Value("${llm.providers.cohere.api-key:}")
    private String apiKey;

    @Value("${llm.providers.cohere.model:command-r-plus}")
    private String defaultModel;

    public CohereService(WebClient webClient, ObjectMapper objectMapper,
                         PromptTemplateService promptTemplateService) {
        super(webClient, objectMapper, promptTemplateService);
    }

    @Override
    public String getProviderName() { return PROVIDER; }

    @Override
    protected String displayName() { return "Cohere"; }

    @Override
    protected String defaultModel() { return defaultModel; }

    @Override
    protected String endpoint(String model) { return API_BASE; }

    @Override
    protected void customizeHeaders(HttpHeaders headers) {
        headers.setBearerAuth(apiKey);
        headers.add("X-Client-Name", "llm-gateway");
    }

    @Override
    protected Map<String, Object> buildPayload(LlmRequest request, String model, String systemText) {
        // Build messages array — system + user
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemText != null && !systemText.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemText));
        }
        messages.add(Map.of("role", "user", "content", request.getPrompt()));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model",       model);
        payload.put("messages",    messages);
        payload.put("max_tokens",  request.getMaxTokens()   != null ? request.getMaxTokens()   : 1024);
        payload.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.7);
        return payload;
    }

    @Override
    protected LlmResponse.LlmResponseBuilder parseResponse(JsonNode root, String model) {
        JsonNode content = root.path("message").path("content");
        String   text    = content.isArray() && content.size() > 0
                ? content.get(0).path("text").asText()
                : root.path("text").asText();  // fallback for v1 shape

        JsonNode usage = root.path("usage").path("billed_units");
        return LlmResponse.builder()
                .content(text)
                .promptTokens(usage.has("input_tokens")      ? usage.path("input_tokens").intValue()  : null)
                .completionTokens(usage.has("output_tokens") ? usage.path("output_tokens").intValue() : null);
    }
}
