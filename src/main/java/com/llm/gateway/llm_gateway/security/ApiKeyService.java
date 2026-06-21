package com.llm.gateway.llm_gateway.security;

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
 * Reactive API-key validation backed by PostgreSQL via R2DBC. Raw keys are never stored — only
 * their SHA-256 hex digest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

  private final DatabaseClient db;

  /**
   * Returns {@code true} if the raw key maps to a row that is enabled and not past its expiry.
   * Always runs on the R2DBC scheduler.
   */
  public Mono<Boolean> isValid(String rawKey) {
    String hash = sha256(rawKey);
    return db.sql(
            """
                SELECT 1 FROM api_keys
                 WHERE key_hash = :hash
                   AND enabled   = TRUE
                   AND (expires_at IS NULL OR expires_at > NOW())
                """)
        .bind("hash", hash)
        .fetch()
        .one()
        .map(row -> true)
        .defaultIfEmpty(false)
        .onErrorResume(
            ex -> {
              log.error("API_KEY | DB lookup failed | error={}", ex.getMessage());
              return Mono.just(false);
            });
  }

  /**
   * Stamps {@code last_used = NOW()} for the key. Fire-and-forget — callers should {@code
   * subscribe()} without blocking.
   */
  public Mono<Void> touchLastUsed(String rawKey) {
    String hash = sha256(rawKey);
    return db.sql("UPDATE api_keys SET last_used = NOW() WHERE key_hash = :hash")
        .bind("hash", hash)
        .then()
        .onErrorResume(
            ex -> {
              log.warn("API_KEY | last_used update failed | error={}", ex.getMessage());
              return Mono.empty();
            });
  }

  static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
