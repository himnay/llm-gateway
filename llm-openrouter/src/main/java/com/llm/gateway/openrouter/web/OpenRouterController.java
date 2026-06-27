package com.llm.gateway.openrouter.web;

import com.llm.gateway.openrouter.dto.OpenRouterRequest;
import com.llm.gateway.openrouter.dto.OpenRouterResponse;
import com.llm.gateway.openrouter.service.OpenRouterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** POST /openrouter/v1/chat — single-turn chat via OpenRouter. */
@Tag(name = "OpenRouter")
@Slf4j
@RestController
@RequiredArgsConstructor
public class OpenRouterController {

  private final OpenRouterService openRouterService;

  @PostMapping("/chat")
  @Operation(
      operationId = "chat",
      summary = "Single-turn chat via OpenRouter",
      description =
          "Sends a prompt to OpenRouter, optionally overriding the configured default model"
              + " (vendor-prefixed, e.g. anthropic/claude-3.5-sonnet).")
  public Mono<OpenRouterResponse> chat(
      @Valid @RequestBody OpenRouterRequest request,
      @RequestHeader(value = "X-Request-ID", required = false) String requestId,
      ServerWebExchange exchange) {
    String cid =
        Optional.ofNullable(requestId)
            .filter(s -> !s.isBlank())
            .orElseGet(() -> UUID.randomUUID().toString());
    request.setCorrelationId(cid);
    exchange.getResponse().getHeaders().set("X-Request-ID", cid);

    log.info("OPENROUTER | chat | model={} | cid={}", request.getModel(), cid);
    return Mono.fromCallable(() -> openRouterService.chat(request))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
