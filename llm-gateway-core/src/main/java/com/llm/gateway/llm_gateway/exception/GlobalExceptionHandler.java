package com.llm.gateway.llm_gateway.exception;

import com.llm.gateway.llm_gateway.security.PromptValidationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway-level WebExceptionHandler — intercepts exceptions that escape functional route handlers
 * (RouterFunctions). @RestControllerAdvice only covers annotation-based controllers; functional
 * routes need this bean instead.
 *
 * <p>Ordered at -2 to run after Spring's ResponseStatusExceptionHandler (-1) but before the default
 * error handler.
 */
@Slf4j
@Order(-2)
@Component
@RequiredArgsConstructor
public class GlobalExceptionHandler implements WebExceptionHandler {

  private final ObjectMapper objectMapper;

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    String path = exchange.getRequest().getPath().value();
    HttpStatus status;
    Map<String, Object> body;

    if (ex instanceof PromptValidationException pve) {
      log.warn("SECURITY | Prompt rejected | violations={}", pve.getViolations());
      status = HttpStatus.BAD_REQUEST;
      body = errorBody(status, "Prompt validation failed", path, pve.getViolations());

    } else if (ex instanceof InvalidRequestException) {
      log.warn("Bad request | {}", ex.getMessage());
      status = HttpStatus.BAD_REQUEST;
      body = errorBody(status, ex.getMessage(), path, List.of());

    } else if (ex instanceof LLMProviderNotSupportedException lpe) {
      log.warn("Unknown provider '{}' | {}", lpe.getProvider(), ex.getMessage());
      status = HttpStatus.BAD_REQUEST;
      body = errorBody(status, ex.getMessage(), path, List.of());

    } else if (ex instanceof java.util.concurrent.TimeoutException) {
      log.warn("Request timed out | {}", ex.getMessage());
      status = HttpStatus.GATEWAY_TIMEOUT;
      body = errorBody(status, "Request timed out", path, List.of());

    } else if (ex instanceof LlmGatewayInternalException) {
      log.error("Internal gateway error", ex);
      status = HttpStatus.INTERNAL_SERVER_ERROR;
      body = errorBody(status, "An internal error occurred. Please try again.", path, List.of());

    } else {
      log.error("Unhandled exception", ex);
      status = HttpStatus.INTERNAL_SERVER_ERROR;
      body = errorBody(status, "An internal error occurred. Please try again.", path, List.of());
    }

    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

    try {
      byte[] bytes = objectMapper.writeValueAsBytes(body);
      DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
      return exchange.getResponse().writeWith(Mono.just(buffer));
    } catch (Exception e) {
      return exchange.getResponse().setComplete();
    }
  }

  private static Map<String, Object> errorBody(HttpStatus status, String message, String path, List<?> details) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status.value());
    body.put("error", status.getReasonPhrase());
    body.put("message", message);
    body.put("timestamp", Instant.now().toString());
    body.put("path", path);
    if (details != null && !details.isEmpty()) {
      body.put("details", details);
    }
    return body;
  }
}
