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
public class HuggingFaceService implements LlmServiceProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.providers.huggingface.api-key}")
    private String apiKey;

    @Value("${llm.providers.huggingface.api-url}")
    private String apiUrl;

    @Value("${llm.providers.huggingface.model:mistralai/Mistral-7B-Instruct-v0.1}")
    private String defaultModel;

    @Override
    public String getProviderName() { return "huggingface"; }

    @Override
    public LlmResponse execute(LlmRequest request) { return textGeneration(request); }

    public LlmResponse textGeneration(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        try {
            log.info("Invoking Hugging Face API | model={}", model);

            String inputs = request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()
                    ? request.getSystemPrompt() + "\n\n" + request.getPrompt()
                    : request.getPrompt();

            Map<String, Object> payload = new HashMap<>();
            payload.put("inputs", inputs);
            payload.put("parameters", Map.of(
                    "max_new_tokens", request.getMaxTokens()   != null ? request.getMaxTokens()   : 512,
                    "temperature",    request.getTemperature() != null ? request.getTemperature() : 0.7,
                    "return_full_text", false
            ));

            String raw = webClient.post()
                    .uri(apiUrl + "/models/" + model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode node = objectMapper.readTree(raw);
            String content = node.isArray() && node.size() > 0
                    ? node.get(0).path("generated_text").asText()
                    : "";

            return LlmResponse.builder()
                    .provider("Hugging Face").model(model)
                    .content(content).response(raw)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("Hugging Face API error | model={}", model, e);
            return LlmResponse.builder()
                    .provider("Hugging Face").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis())
                    .build();
        }
    }
}
