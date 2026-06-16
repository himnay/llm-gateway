package com.llm.gateway.llm_gateway.handler;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.guardrail.chain.GuardrailChain;
import com.llm.gateway.llm_gateway.guardrail.chain.GuardrailContext;
import com.llm.gateway.llm_gateway.security.PromptSanitizer;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.service.AnthropicClaudeService;
import com.llm.gateway.llm_gateway.service.OllamaService;
import com.llm.gateway.llm_gateway.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SSE streaming handler: POST /llm/{provider}/stream
 *
 * Runs the inbound guardrail chain before opening the stream so prompt
 * injection and PII are caught even for streaming requests. Enforces a
 * per-stream timeout and returns structured error events on failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmStreamHandler {

    private final OpenAiService      openAiService;
    private final AnthropicClaudeService anthropicService;
    private final OllamaService      ollamaService;
    private final GuardrailChain     guardrailChain;
    private final PromptSanitizer    promptSanitizer;

    @Value("${llm.stream.timeout-seconds:120}")
    private int streamTimeoutSeconds;

    public Mono<ServerResponse> stream(ServerRequest req) {
        String provider = req.pathVariable("provider");
        String cid = correlationId(req);

        return req.bodyToMono(LlmRequest.class)
                .flatMap(request -> {
                    request.setCorrelationId(cid);

                    if (request.getPrompt() == null || request.getPrompt().isBlank()) {
                        return ServerResponse.badRequest()
                                .bodyValue(Map.of("error", "prompt is required"));
                    }

                    // Run guardrail chain synchronously (blocking on bounded-elastic
                    // so the reactive chain isn't blocked on the event-loop thread).
                    return Mono.fromCallable(() -> {
                                GuardrailContext ctx = new GuardrailContext(provider, cid, request);
                                guardrailChain.apply(ctx);
                                return request;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(validated -> {
                                log.info("HANDLER | stream | provider={} | session={} | cid={}",
                                        provider, validated.getSessionId(), cid);

                                Flux<String> tokens = switch (provider) {
                                    case "openai"    -> openAiService.stream(validated);
                                    case "anthropic" -> anthropicService.stream(validated);
                                    case "ollama"    -> ollamaService.stream(validated);
                                    default -> Flux.error(new IllegalArgumentException(
                                            "Streaming not supported for provider: " + provider));
                                };

                                return ServerResponse.ok()
                                        .contentType(MediaType.TEXT_EVENT_STREAM)
                                        .header("X-Request-ID", cid)
                                        .body(tokens.timeout(Duration.ofSeconds(streamTimeoutSeconds))
                                                .onErrorResume(ex -> {
                                                    log.error("STREAM | error | provider={} | cid={} | {}", provider, cid, ex.getMessage());
                                                    return Flux.just("data: [ERROR] " + ex.getMessage() + "\n\n");
                                                }), String.class);
                            });
                })
                .onErrorResume(PromptValidationException.class, pve -> ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "Prompt validation failed", "details", pve.getViolations())))
                .onErrorResume(ex -> {
                    log.error("STREAM | setup failed | cid={} | {}", cid, ex.getMessage());
                    return ServerResponse.badRequest()
                            .bodyValue(Map.of("error", ex.getMessage()));
                });
    }

    private static String correlationId(ServerRequest req) {
        return Optional.ofNullable(req.headers().firstHeader("X-Request-ID"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
    }
}
