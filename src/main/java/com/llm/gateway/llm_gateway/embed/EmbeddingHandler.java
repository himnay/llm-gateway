package com.llm.gateway.llm_gateway.embed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Embedding handler: POST /llm/embed
 *
 * Generates a vector embedding for the given text using OpenAI's embedding model
 * (text-embedding-3-small by default). Both Ollama and OpenAI starters register
 * EmbeddingModel beans; the OpenAI one is explicitly selected here.
 */
@Slf4j
@Component
public class EmbeddingHandler {

    private final EmbeddingModel embeddingModel;

    public EmbeddingHandler(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Mono<ServerResponse> embed(ServerRequest req) {
        String cid = Optional.ofNullable(req.headers().firstHeader("X-Request-ID"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());

        return req.bodyToMono(com.llm.gateway.llm_gateway.embed.EmbeddingRequest.class)
                .flatMap(request -> {
                    if (request.getText() == null || request.getText().isBlank()) {
                        return ServerResponse.badRequest()
                                .bodyValue(Map.of("error", "'text' is required"));
                    }

                    log.info("HANDLER | embed | cid={} | textLength={}", cid, request.getText().length());

                    return Mono.fromCallable(() -> {
                                EmbeddingResponse response = embeddingModel.call(
                                        new EmbeddingRequest(List.of(request.getText()), null));

                                float[] raw = response.getResult().getOutput();
                                List<Double> vec = new java.util.ArrayList<>(raw.length);
                                for (float v : raw) vec.add((double) v);

                                String model = response.getMetadata() != null
                                        ? response.getMetadata().getModel() : "unknown";
                                return com.llm.gateway.llm_gateway.embed.EmbeddingResponse.builder()
                                        .provider("openai")
                                        .model(model)
                                        .embedding(vec)
                                        .dimensions(raw.length)
                                        .requestId(cid)
                                        .timestamp(System.currentTimeMillis())
                                        .build();
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(Duration.ofSeconds(30))
                            .flatMap(resp -> ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header("X-Request-ID", cid)
                                    .bodyValue(resp));
                })
                .onErrorResume(ex -> {
                    log.error("HANDLER | embed failed | cid={} | {}", cid, ex.getMessage());
                    return ServerResponse.status(502)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(com.llm.gateway.llm_gateway.embed.EmbeddingResponse.builder()
                                    .error(ex.getMessage())
                                    .requestId(cid)
                                    .timestamp(System.currentTimeMillis())
                                    .build());
                });
    }
}
