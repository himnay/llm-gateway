package com.llm.gateway.llm_gateway.audit;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.exception.LlmGatewayInternalException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Fire-and-forget audit logger.
 *
 * <p>Called by {@link com.llm.gateway.llm_gateway.facade.LlmGatewayFacade} after every request.
 * Errors are swallowed so a DB outage never impacts LLM traffic. Raw prompts are NEVER stored —
 * only their SHA-256 hash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestLogService {

  private final DatabaseClient db;

  /**
   * Persists an audit entry as a reactive chain. Callers subscribe fire-and-forget;
   * errors are swallowed so a DB outage never impacts LLM traffic.
   */
  public Mono<Void> log(
      String requestId, String clientId, LlmRequest request, LlmResponse response) {
    String promptHash = request.getPrompt() != null ? sha256(request.getPrompt()) : null;
    return db.sql(
                """
                      INSERT INTO request_log
                          (request_id, correlation_id, provider, model, client_id,
                           prompt_hash, prompt_length, cache_hit, latency_ms,
                           prompt_tokens, completion_tokens, total_tokens, error, sanitized)
                      VALUES
                          (:requestId, :correlationId, :provider, :model, :clientId,
                           :promptHash, :promptLength, :cacheHit, :latencyMs,
                           :promptTokens, :completionTokens, :totalTokens, :error, :sanitized)
                      """)
        .bind("requestId", requestId)
        .bind("correlationId", response.getCorrelationId())
        .bind("provider", response.getProvider() != null ? response.getProvider() : "unknown")
        .bind("model", response.getModel())
        .bind("clientId", clientId)
        .bind("promptHash", promptHash)
        .bind("promptLength", request.getPrompt() != null ? request.getPrompt().length() : 0)
        .bind("cacheHit", Boolean.TRUE.equals(response.getCacheHit()))
        .bind("latencyMs", response.getLatencyMs())
        .bind("promptTokens", response.getPromptTokens())
        .bind("completionTokens", response.getCompletionTokens())
        .bind("totalTokens", response.getTotalTokens())
        .bind("error", response.getError())
        .bind("sanitized", Boolean.TRUE.equals(response.getSanitized()))
        .then()
        .onErrorResume(e -> {
          log.warn("AUDIT | write error (non-fatal) | requestId={} | error={}", requestId, e.getMessage());
          return Mono.empty();
        });
  }

  private static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new LlmGatewayInternalException("SHA-256 unavailable", e);
    }
  }
}
