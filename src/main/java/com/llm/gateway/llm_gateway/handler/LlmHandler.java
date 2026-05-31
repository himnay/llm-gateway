package com.llm.gateway.llm_gateway.handler;

import com.llm.gateway.llm_gateway.dto.LlmProvider;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.dto.StructuredLlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmGatewayFacade;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmHandler {

    private final LlmGatewayFacade facade;
    private final OpenAiService openAiService;

    public Mono<ServerResponse> query(ServerRequest req) {
        return req.bodyToMono(LlmRequest.class)
                .flatMap(request -> {
                    String provider = request.getProvider() != null ? request.getProvider().key() : LlmProvider.OPENAI.key();
                    log.info("HANDLER | query | provider={} | session={}", provider, request.getSessionId());
                    return Mono.fromCallable(() -> facade.execute(provider, request))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .flatMap(this::ok)
                .onErrorResume(this::errorResponse);
    }

    /**
     * Tries each provider in the supplied chain until one succeeds.
     * Default chain: openai → anthropic → ollama.
     * Request body: {"prompt":"...", "providers":["openai","anthropic","ollama"]}
     */
    public Mono<ServerResponse> failoverQuery(ServerRequest req) {
        return req.bodyToMono(LlmRequest.class)
                .flatMap(request -> {
                    List<String> chain = (request.getProviders() != null && !request.getProviders().isEmpty())
                            ? request.getProviders().stream().map(LlmProvider::key).toList()
                            : List.of(LlmProvider.OPENAI.key(), LlmProvider.ANTHROPIC.key(), LlmProvider.OLLAMA.key());
                    log.info("HANDLER | failover | chain={} | session={}", chain, request.getSessionId());
                    return Mono.fromCallable(() -> facade.executeWithFailover(chain, request))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .flatMap(this::ok)
                .onErrorResume(this::errorResponse);
    }

    /** Dynamic per-provider route: POST /api/llm/{provider}/chat */
    public Mono<ServerResponse> perProviderChat(ServerRequest req) {
        String provider = req.pathVariable("provider");
        return req.bodyToMono(LlmRequest.class)
                .flatMap(request -> Mono.fromCallable(() -> facade.execute(provider, request))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(this::ok)
                .onErrorResume(this::errorResponse);
    }

    /** Multi-turn chat — session_id is mandatory. */
    public Mono<ServerResponse> chat(ServerRequest req) {
        return req.bodyToMono(LlmRequest.class)
                .flatMap(request -> {
                    if (request.getSessionId() == null || request.getSessionId().isBlank()) {
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(LlmResponse.builder()
                                        .error("session_id is required for multi-turn /chat")
                                        .timestamp(System.currentTimeMillis())
                                        .build());
                    }
                    String provider = request.getProvider() != null ? request.getProvider().key() : LlmProvider.OPENAI.key();
                    log.info("HANDLER | chat | provider={} | session={}", provider, request.getSessionId());
                    return Mono.fromCallable(() -> facade.execute(provider, request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(this::ok);
                })
                .onErrorResume(this::errorResponse);
    }

    /** Structured JSON extraction via Spring AI BeanOutputConverter. */
    public Mono<ServerResponse> extractStructured(ServerRequest req) {
        return req.bodyToMono(LlmRequest.class)
                .flatMap(request -> Mono.fromCallable(
                                () -> openAiService.extractStructured(request, StructuredLlmResponse.class))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(this::ok)
                .onErrorResume(this::errorResponse);
    }

    public Mono<ServerResponse> health(ServerRequest req) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "UP", "service", "LLM Gateway", "version", "2.0.0-spring-ai"));
    }

    public Mono<ServerResponse> providers(ServerRequest req) {
        Set<String> registered = facade.getRegisteredProviders();
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("count", registered.size(), "providers", registered));
    }

    public Mono<ServerResponse> models(ServerRequest req) {
        Map<String, Object> models = new HashMap<>();
        models.put("openai",      new String[]{"gpt-4o", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo"});
        models.put("google",      new String[]{"gemini-1.5-pro-latest", "gemini-1.5-flash-latest"});
        models.put("anthropic",   new String[]{"claude-3-5-sonnet-20241022", "claude-3-opus-20240229", "claude-3-haiku-20240307"});
        models.put("ollama",      new String[]{"llama3.1", "mistral", "phi3", "neural-chat"});
        models.put("huggingface", new String[]{"mistralai/Mistral-7B-Instruct-v0.1", "meta-llama/Llama-2-7b-chat"});
        models.put("cohere",      new String[]{"command-r-plus", "command-r", "command-light"});
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(models);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private Mono<ServerResponse> errorResponse(Throwable ex) {
        log.error("HANDLER | error | {}", ex.getMessage(), ex);
        if (ex instanceof PromptValidationException pve) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "Prompt validation failed", "details", pve.getViolations()));
        }
        if (ex instanceof IllegalArgumentException) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", ex.getMessage()));
        }
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyValue(Map.of("error", "Internal error", "message", ex.getMessage()));
    }
}
