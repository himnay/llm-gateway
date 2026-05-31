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

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CohereService implements LlmServiceProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.providers.cohere.api-key}")
    private String apiKey;

    @Value("${llm.providers.cohere.api-url}")
    private String apiUrl;

    @Value("${llm.providers.cohere.model:command-r-plus}")
    private String defaultModel;

    @Override
    public String getProviderName() { return "cohere"; }

    @Override
    public LlmResponse execute(LlmRequest request) { return generate(request); }

    public LlmResponse generate(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        try {
            log.info("Invoking Cohere API | model={}", model);

            Map<String, Object> payload = new HashMap<>();
            payload.put("model",       model);
            payload.put("message",     request.getPrompt());
            payload.put("max_tokens",  request.getMaxTokens()   != null ? request.getMaxTokens()   : 1024);
            payload.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.7);
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                payload.put("preamble", request.getSystemPrompt());
            }

            String raw = webClient.post()
                    .uri(apiUrl + "/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode node = objectMapper.readTree(raw);
            return LlmResponse.builder()
                    .provider("Cohere").model(model)
                    .content(node.path("text").asText())
                    .response(raw)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("Cohere API error | model={}", model, e);
            return LlmResponse.builder()
                    .provider("Cohere").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis())
                    .build();
        }
    }
}
