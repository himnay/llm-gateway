package com.llm.gateway.llm_gateway.image;

import com.llm.gateway.llm_gateway.dto.ImageGenerationRequest;
import com.llm.gateway.llm_gateway.dto.ImageGenerationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

/**
 * Reactive handler for {@code POST /llm/image}. Validates the prompt, offloads the blocking
 * image-model call to the bounded-elastic scheduler and returns a JSON
 * {@link ImageGenerationResponse}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageHandler {

    private final ImageService imageService;

    @Value("${llm.image.timeout-seconds:60}")
    private int timeoutSeconds;

    public Mono<ServerResponse> generate(ServerRequest req) {
        String cid = correlationId(req);
        return req.bodyToMono(ImageGenerationRequest.class)
                .flatMap(request -> {
                    if (request.getPrompt() == null || request.getPrompt().isBlank()) {
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(ImageGenerationResponse.builder()
                                        .error("prompt is required")
                                        .correlationId(cid)
                                        .timestamp(System.currentTimeMillis())
                                        .build());
                    }
                    request.setCorrelationId(cid);
                    log.info("HANDLER | image | model={} | cid={}", request.getModel(), cid);
                    return Mono.fromCallable(() -> imageService.generate(request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .flatMap(resp -> ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header("X-Request-ID", cid)
                                    .bodyValue(resp));
                })
                .onErrorResume(ex -> {
                    log.error("HANDLER | image failed | cid={} | error={}", cid, ex.getMessage());
                    return ServerResponse.status(502)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(ImageGenerationResponse.builder()
                                    .error(ex.getMessage())
                                    .correlationId(cid)
                                    .timestamp(System.currentTimeMillis())
                                    .build());
                });
    }

    private String correlationId(ServerRequest req) {
        return req.headers().firstHeader("X-Request-ID") != null
                ? req.headers().firstHeader("X-Request-ID")
                : UUID.randomUUID().toString();
    }
}
