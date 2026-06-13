package com.llm.gateway.llm_gateway.handler;

import com.llm.gateway.llm_gateway.dto.LlmProvider;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.exception.InvalidRequestException;
import com.llm.gateway.llm_gateway.exception.LLMProviderNotSupportedException;
import com.llm.gateway.llm_gateway.facade.LlmGatewayFacade;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmHandler {

    private final LlmGatewayFacade facade;
    private final StringRedisTemplate redisTemplate;
    private final ChatMemoryRepository chatMemoryRepository;

    @Value("${llm.request.timeout-seconds:30}")
    private int timeoutSeconds;

    // ── Routes ────────────────────────────────────────────────────────────────

    public Mono<ServerResponse> query(ServerRequest req) {
        String cid = correlationId(req);
        return req.bodyToMono(LlmRequest.class)
                .doOnNext(r -> { r.setCorrelationId(cid); validate(r); })
                .flatMap(request -> {
                    String provider = request.getProvider() != null
                            ? request.getProvider().key() : LlmProvider.OPENAI.key();
                    log.info("HANDLER | query | provider={} | session={} | cid={}", provider, request.getSessionId(), cid);
                    // Auto-failover: if OpenAI key is wrong, gateway silently routes to next provider
                    return Mono.fromCallable(() -> facade.executeWithAutoFailover(provider, request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(Duration.ofSeconds(timeoutSeconds));
                })
                .flatMap(resp -> ok(resp, cid))
                .onErrorResume(this::errorResponse);
    }

    /** Tries each provider in the supplied chain until one succeeds. */
    public Mono<ServerResponse> failoverQuery(ServerRequest req) {
        String cid = correlationId(req);
        return req.bodyToMono(LlmRequest.class)
                .doOnNext(r -> { r.setCorrelationId(cid); validate(r); })
                .flatMap(request -> {
                    List<String> chain = (request.getProviders() != null && !request.getProviders().isEmpty())
                            ? request.getProviders().stream().map(LlmProvider::key).toList()
                            : List.of(LlmProvider.OPENAI.key(), LlmProvider.ANTHROPIC.key(), LlmProvider.OLLAMA.key());
                    log.info("HANDLER | failover | chain={} | cid={}", chain, cid);
                    return Mono.fromCallable(() -> facade.executeWithFailover(chain, request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(Duration.ofSeconds(timeoutSeconds));
                })
                .flatMap(resp -> ok(resp, cid))
                .onErrorResume(this::errorResponse);
    }

    /** Dynamic per-provider route: POST /llm/{provider}/chat */
    public Mono<ServerResponse> perProviderChat(ServerRequest req) {
        String provider = req.pathVariable("provider");
        String cid = correlationId(req);
        return req.bodyToMono(LlmRequest.class)
                .doOnNext(r -> { r.setCorrelationId(cid); validate(r); })
                .flatMap(request -> Mono.fromCallable(() -> facade.execute(provider, request))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(Duration.ofSeconds(timeoutSeconds)))
                .flatMap(resp -> ok(resp, cid))
                .onErrorResume(this::errorResponse);
    }

    /** Multi-turn chat — session_id is mandatory. */
    public Mono<ServerResponse> chat(ServerRequest req) {
        String cid = correlationId(req);
        return req.bodyToMono(LlmRequest.class)
                .doOnNext(r -> { r.setCorrelationId(cid); validate(r); })
                .flatMap(request -> {
                    if (request.getSessionId() == null || request.getSessionId().isBlank()) {
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(LlmResponse.builder()
                                        .error("session_id is required for multi-turn /chat")
                                        .timestamp(System.currentTimeMillis()).build());
                    }
                    String provider = request.getProvider() != null
                            ? request.getProvider().key() : LlmProvider.OPENAI.key();
                    log.info("HANDLER | chat | provider={} | session={} | cid={}", provider, request.getSessionId(), cid);
                    // Auto-failover: auth/config errors silently route to next provider
                    return Mono.fromCallable(() -> facade.executeWithAutoFailover(provider, request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .flatMap(resp -> ok(resp, cid));
                })
                .onErrorResume(this::errorResponse);
    }

    /** Structured JSON extraction — routed through facade for full observability. */
    public Mono<ServerResponse> extractStructured(ServerRequest req) {
        String cid = correlationId(req);
        return req.bodyToMono(LlmRequest.class)
                .doOnNext(r -> { r.setCorrelationId(cid); validate(r); })
                .flatMap(request -> Mono.fromCallable(() -> facade.executeStructured(request))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(Duration.ofSeconds(timeoutSeconds)))
                .flatMap(resp -> ok(resp, cid))
                .onErrorResume(this::errorResponse);
    }

    /** Checks gateway liveness and Redis connectivity. */
    public Mono<ServerResponse> health(ServerRequest req) {
        return Mono.fromCallable(() -> {
            boolean redisOk;
            String  redisDetail;
            try {
                // A get on a non-existent key returns null if Redis is reachable
                redisTemplate.opsForValue().get("__health_probe__");
                redisOk    = true;
                redisDetail = "UP";
            } catch (Exception e) {
                redisOk    = false;
                redisDetail = "DOWN — " + e.getMessage();
            }
            Map<String, Object> body = new HashMap<>();
            body.put("status",  redisOk ? "UP" : "DEGRADED");
            body.put("service", "LLM Gateway");
            body.put("version", "2.0.0-spring-ai");
            body.put("redis",   redisDetail);
            return body;
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(this::ok)
          .onErrorResume(this::errorResponse);
    }

    public Mono<ServerResponse> providers(ServerRequest req) {
        Set<String> registered = facade.getRegisteredProviders();
        return ok(Map.of("count", registered.size(), "providers", registered));
    }

    public Mono<ServerResponse> models(ServerRequest req) {
        Map<String, Object> models = new HashMap<>();
        models.put("openai",      new String[]{"gpt-4o", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo"});
        models.put("anthropic",   new String[]{"claude-3-5-sonnet-20241022", "claude-3-opus-20240229", "claude-3-haiku-20240307"});
        models.put("ollama",      new String[]{"llama3.1", "mistral", "phi3", "neural-chat"});
        models.put("google",      new String[]{"gemini-1.5-pro-latest", "gemini-1.5-flash-latest"});
        models.put("huggingface", new String[]{"mistralai/Mistral-7B-Instruct-v0.1", "meta-llama/Llama-2-7b-chat"});
        models.put("cohere",      new String[]{"command-r-plus", "command-r", "command-light"});
        return ok(models);
    }

    /** Clears all Redis-stored conversation history for a session. */
    public Mono<ServerResponse> deleteSession(ServerRequest req) {
        String sessionId = req.pathVariable("sessionId");
        return Mono.fromRunnable(() -> {
                    chatMemoryRepository.deleteByConversationId(sessionId);
                    log.info("HANDLER | session deleted | sessionId={}", sessionId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(ServerResponse.noContent().build())
                .onErrorResume(this::errorResponse);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String correlationId(ServerRequest req) {
        return Optional.ofNullable(req.headers().firstHeader("X-Request-ID"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    private static void validate(LlmRequest request) {
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new InvalidRequestException("'prompt' is required and must not be blank");
        }
        if (request.getPrompt().length() > 10_000) {
            throw new InvalidRequestException("'prompt' exceeds the maximum allowed length of 10,000 characters");
        }
    }

    private Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private Mono<ServerResponse> ok(Object body, String correlationId) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-ID", correlationId)
                .bodyValue(body);
    }

    private Mono<ServerResponse> errorResponse(Throwable ex) {
        log.error("HANDLER | error | {}", ex.getMessage(), ex);
        if (ex instanceof PromptValidationException pve) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "Prompt validation failed", "details", pve.getViolations()));
        }
        if (ex instanceof InvalidRequestException || ex instanceof LLMProviderNotSupportedException) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", ex.getMessage()));
        }
        if (ex instanceof TimeoutException) {
            return ServerResponse.status(HttpStatus.GATEWAY_TIMEOUT)
                    .bodyValue(Map.of("error", "Request timed out",
                            "message", "Provider did not respond within " + timeoutSeconds + "s"));
        }
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyValue(Map.of("error", "Internal error", "message", ex.getMessage()));
    }
}
