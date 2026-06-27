package com.llm.gateway.llm_gateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.exception.LLMProviderNotSupportedException;
import com.llm.gateway.llm_gateway.facade.LlmProviderRegistry;
import com.llm.gateway.llm_gateway.guardrail.chain.GuardrailChain;
import com.llm.gateway.llm_gateway.guardrail.chain.GuardrailContext;
import com.llm.gateway.llm_gateway.security.PromptSanitizer;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * SSE streaming handler: POST /llm/{provider}/stream
 *
 * <p>Runs the inbound guardrail chain before opening the stream so prompt injection and PII are
 * caught even for streaming requests. Enforces a per-stream timeout and returns structured error
 * events on failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmStreamHandler {

  /**
   * Canonical SSE error payload for the streaming endpoint. Serialised to JSON and sent as {@code
   * event: error} frames.
   */
  record StreamErrorEvent(String type, String code, String message, String requestId) {}

  private final LlmProviderRegistry providerRegistry;
  private final GuardrailChain guardrailChain;
  private final PromptSanitizer promptSanitizer;
  private final ObjectMapper objectMapper;

  @Value("${llm.stream.timeout-seconds:120}")
  private int streamTimeoutSeconds;

  public Mono<ServerResponse> stream(ServerRequest req) {
    String provider = req.pathVariable("provider");
    String cid = correlationId(req);

    return req.bodyToMono(LlmRequest.class)
        .flatMap(
            request -> {
              request.setCorrelationId(cid);

              if (request.getPrompt() == null || request.getPrompt().isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "prompt is required"));
              }

              // Run guardrail chain synchronously (blocking on bounded-elastic
              // so the reactive chain isn't blocked on the event-loop thread).
              return Mono.fromCallable(
                      () -> {
                        GuardrailContext ctx = new GuardrailContext(provider, cid, request);
                        guardrailChain.apply(ctx);
                        return request;
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(
                      validated -> {
                        log.info(
                            "HANDLER | stream | provider={} | session={} | cid={}",
                            provider,
                            validated.getSessionId(),
                            cid);

                        Flux<String> tokens;
                        try {
                          tokens = providerRegistry.resolve(provider).stream(validated);
                        } catch (LLMProviderNotSupportedException | UnsupportedOperationException ex) {
                          tokens = Flux.error(new IllegalArgumentException(
                              "Streaming not supported for provider: " + provider));
                        }

                        Flux<ServerSentEvent<String>> sseStream =
                            tokens
                                .timeout(Duration.ofSeconds(streamTimeoutSeconds))
                                .map(
                                    token ->
                                        ServerSentEvent.<String>builder()
                                            .event("token")
                                            .data(token)
                                            .build())
                                .onErrorResume(
                                    ex -> {
                                      log.error(
                                          "STREAM | error | provider={} | cid={} | {}",
                                          provider,
                                          cid,
                                          ex.getMessage());
                                      return Flux.just(
                                          buildErrorEvent(cid, "PROVIDER_ERROR", ex.getMessage()));
                                    });

                        return ServerResponse.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .header("X-Request-ID", cid)
                            .body(sseStream, ServerSentEvent.class);
                      });
            })
        .onErrorResume(
            PromptValidationException.class,
            pve ->
                ServerResponse.badRequest()
                    .bodyValue(
                        Map.of(
                            "error", "Prompt validation failed", "details", pve.getViolations())))
        .onErrorResume(
            ex -> {
              log.error("STREAM | setup failed | cid={} | {}", cid, ex.getMessage());
              return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
            });
  }

  /**
   * Builds a standardised SSE error frame:
   *
   * <pre>
   * event: error
   * data: {"type":"error","code":"...","message":"...","requestId":"..."}
   * </pre>
   */
  private ServerSentEvent<String> buildErrorEvent(String requestId, String code, String message) {
    String safeMessage = message != null ? message : "Unknown error";
    String data;
    try {
      data =
          objectMapper.writeValueAsString(
              new StreamErrorEvent("error", code, safeMessage, requestId));
    } catch (JsonProcessingException e) {
      data =
          "{\"type\":\"error\",\"code\":\""
              + code
              + "\",\"message\":\"serialization error\",\"requestId\":\""
              + requestId
              + "\"}";
    }
    return ServerSentEvent.<String>builder().event("error").data(data).build();
  }

  private static String correlationId(ServerRequest req) {
    return Optional.ofNullable(req.headers().firstHeader("X-Request-ID"))
        .filter(s -> !s.isBlank())
        .orElseGet(() -> UUID.randomUUID().toString());
  }
}
