package com.llm.gateway.llm_gateway.guardrail.remote;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GoF <b>Adapter</b> — wraps the LangChain guardrails sidecar's REST API
 * ({@code POST /v1/validate}) behind a typed Java interface so the rest of the
 * gateway never deals with HTTP/JSON details.
 *
 * <p>Protected by its own Resilience4j circuit breaker ({@code guardrails-service});
 * when the sidecar is down or the circuit is open, the fallback applies the configured
 * availability policy: <i>fail-open</i> lets traffic continue (logged + metered),
 * <i>fail-closed</i> rejects the request.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteGuardrailClient {

    static final String STAGE_INPUT  = "input";
    static final String STAGE_OUTPUT = "output";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final RemoteGuardrailProperties properties;
    private final LlmMetricsService metricsService;

    /**
     * Validates {@code text} against the sidecar.
     *
     * @param stage {@code "input"} (prompt, pre-LLM) or {@code "output"} (response, post-LLM)
     */
    @CircuitBreaker(name = "guardrails-service", fallbackMethod = "availabilityFallback")
    public GuardrailValidationResult validate(String text, String stage) {
        String raw = webClient.post()
                .uri(properties.getBaseUrl() + "/v1/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text, "stage", stage))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .block();

        JsonNode root = objectMapper.readTree(raw);
        List<String> violations = new ArrayList<>();
        root.path("violations").forEach(v -> violations.add(v.asText()));

        return new GuardrailValidationResult(
                root.path("passed").asBoolean(true),
                violations,
                root.hasNonNull("sanitized_text") ? root.path("sanitized_text").asText() : null,
                root.path("risk_score").asDouble(-1));
    }

    /**
     * Resilience4j fallback — invoked on transport errors, timeouts, or an open circuit.
     * Never invoked for guardrail violations (those are a successful HTTP exchange).
     */
    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private GuardrailValidationResult availabilityFallback(String text, String stage, Throwable cause) {
        metricsService.recordError("guardrails-service", cause.getClass().getSimpleName());
        if (properties.isFailOpen()) {
            log.warn("GUARDRAIL | sidecar unavailable — failing OPEN (request continues) | stage={} | cause={}",
                    stage, cause.toString());
            return GuardrailValidationResult.passedResult();
        }
        log.error("GUARDRAIL | sidecar unavailable — failing CLOSED (request rejected) | stage={}", stage, cause);
        throw new PromptValidationException(List.of(
                "Guardrails service is unavailable and the gateway is configured fail-closed "
                        + "(llm.guardrails.external.fail-open=false)."));
    }
}
