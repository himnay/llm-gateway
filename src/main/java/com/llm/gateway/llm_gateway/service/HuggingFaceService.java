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
 * HuggingFace Serverless Inference API — OpenAI-compatible chat endpoint.
 * Endpoint: POST https://api-inference.huggingface.co/v1/chat/completions
 * Docs: https://huggingface.co/docs/api-inference/tasks/chat-completion
 *
 * This endpoint is compatible with any model that supports the chat completion
 * task on the HuggingFace Hub (e.g. Mistral, Llama, Qwen, Phi).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "llm.providers.huggingface", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HuggingFaceService implements LlmServiceProvider {

    private static final String PROVIDER = "huggingface";
    private static final String CHAT_ENDPOINT =
            "https://api-inference.huggingface.co/v1/chat/completions";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;

    @Value("${llm.providers.huggingface.api-key:}")
    private String apiKey;

    @Value("${llm.providers.huggingface.model:mistralai/Mistral-7B-Instruct-v0.1}")
    private String defaultModel;

    @Override
    public String getProviderName() { return PROVIDER; }

    @Override
    public LlmResponse execute(LlmRequest request) {
        String model      = request.getModel() != null ? request.getModel() : defaultModel;
        String systemText = promptTemplateService.renderSystemPrompt(PROVIDER, request);

        log.info("Invoking HuggingFace Inference API | model={}", model);

        try {
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

            String raw = webClient.post()
                    .uri(CHAT_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root    = objectMapper.readTree(raw);
            JsonNode choice  = root.path("choices").get(0);
            String   text    = choice.path("message").path("content").asText();

            JsonNode usage = root.path("usage");
            return LlmResponse.builder()
                    .provider("Hugging Face").model(model)
                    .content(text)
                    .promptTokens(usage.has("prompt_tokens")     ? usage.path("prompt_tokens").intValue()     : null)
                    .completionTokens(usage.has("completion_tokens") ? usage.path("completion_tokens").intValue() : null)
                    .totalTokens(usage.has("total_tokens")       ? usage.path("total_tokens").intValue()       : null)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("HuggingFace API error | model={}", model, e);
            return LlmResponse.builder().provider("Hugging Face").model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis()).build();
        }
    }
}
