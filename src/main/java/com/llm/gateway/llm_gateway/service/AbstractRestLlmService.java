package com.llm.gateway.llm_gateway.service;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmServiceProvider;
import com.llm.gateway.llm_gateway.template.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * GoF <b>Template Method</b> — skeleton of a REST-based LLM provider call.
 *
 * <p>{@link #execute(LlmRequest)} fixes the invariant algorithm (resolve model, render
 * system prompt, POST JSON, parse, never throw — errors become
 * {@link LlmResponse#getError()}), while subclasses supply only the provider-specific
 * hooks: endpoint, headers, request payload and response parsing. Used by the
 * providers that talk to plain REST APIs (Google Gemini, Cohere, HuggingFace);
 * the Spring AI providers (OpenAI, Anthropic, Ollama) go through ChatClient instead.</p>
 */
public abstract class AbstractRestLlmService implements LlmServiceProvider {

    /** Logger named after the concrete subclass so log lines stay attributable. */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final WebClient webClient;
    protected final ObjectMapper objectMapper;
    protected final PromptTemplateService promptTemplateService;

    protected AbstractRestLlmService(WebClient webClient,
                                     ObjectMapper objectMapper,
                                     PromptTemplateService promptTemplateService) {
        this.webClient             = webClient;
        this.objectMapper          = objectMapper;
        this.promptTemplateService = promptTemplateService;
    }

    /** Template method — final so the error-handling contract cannot be broken. */
    @Override
    public final LlmResponse execute(LlmRequest request) {
        String model      = request.getModel() != null ? request.getModel() : defaultModel();
        String systemText = promptTemplateService.renderSystemPrompt(getProviderName(), request);

        log.info("Invoking {} API | model={}", displayName(), model);
        try {
            String raw = webClient.post()
                    .uri(endpoint(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::customizeHeaders)
                    .bodyValue(buildPayload(request, model, systemText))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseResponse(objectMapper.readTree(raw), model)
                    .provider(displayName())
                    .model(model)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("{} API error | model={}", displayName(), model, e);
            return LlmResponse.builder().provider(displayName()).model(model)
                    .error(e.getMessage()).timestamp(System.currentTimeMillis()).build();
        }
    }

    // ── Hooks implemented by each provider ────────────────────────────────────

    /** Human-readable provider name placed in {@code LlmResponse.provider}. */
    protected abstract String displayName();

    /** Model used when the request doesn't specify one. */
    protected abstract String defaultModel();

    /** Full request URL (may embed the model or API key). */
    protected abstract String endpoint(String model);

    /** Override to add auth/extra headers; default adds nothing. */
    protected void customizeHeaders(HttpHeaders headers) {
        // no-op by default
    }

    /** Provider-specific JSON request body. */
    protected abstract Map<String, Object> buildPayload(LlmRequest request, String model, String systemText);

    /**
     * Extracts content/usage from the provider's JSON. Return a partially-populated
     * builder — provider, model and timestamp are filled in by the template.
     */
    protected abstract LlmResponse.LlmResponseBuilder parseResponse(JsonNode root, String model);
}
