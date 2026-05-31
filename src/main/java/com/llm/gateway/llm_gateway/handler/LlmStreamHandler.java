package com.llm.gateway.llm_gateway.handler;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.service.AnthropicClaudeService;
import com.llm.gateway.llm_gateway.service.OllamaService;
import com.llm.gateway.llm_gateway.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmStreamHandler {

    private final OpenAiService openAiService;
    private final AnthropicClaudeService anthropicService;
    private final OllamaService ollamaService;

    /**
     * Unified SSE streaming route: POST /api/llm/{provider}/stream
     * Supported providers: openai, anthropic, ollama
     */
    public Mono<ServerResponse> stream(ServerRequest req) {
        String provider = req.pathVariable("provider");
        return req.bodyToMono(LlmRequest.class)
                .flatMap(request -> {
                    log.info("HANDLER | stream | provider={} | session={}", provider, request.getSessionId());
                    return switch (provider) {
                        case "openai" -> ServerResponse.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .body(openAiService.stream(request), String.class);
                        case "anthropic" -> ServerResponse.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .body(anthropicService.stream(request), String.class);
                        case "ollama" -> ServerResponse.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .body(ollamaService.stream(request), String.class);
                        default -> ServerResponse.badRequest()
                                .bodyValue(Map.of("error", "Streaming not supported for provider: " + provider));
                    };
                });
    }
}
