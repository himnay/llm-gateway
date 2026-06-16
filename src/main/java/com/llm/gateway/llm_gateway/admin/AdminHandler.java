package com.llm.gateway.llm_gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin REST handler for API key lifecycle management.
 *
 * Routes (all under /llm/admin/, require auth):
 *   GET    /admin/keys          — list all keys (names, status; no hashes)
 *   POST   /admin/keys          — create a new key (returns raw key ONCE)
 *   PATCH  /admin/keys/{id}     — enable / disable a key or set expiry
 *   DELETE /admin/keys/{id}     — permanently delete a key
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminHandler {

    private final DatabaseClient db;

    public Mono<ServerResponse> listKeys(ServerRequest req) {
        return db.sql("""
                SELECT id, name, client_id, enabled, created_at, expires_at, last_used
                FROM api_keys ORDER BY created_at DESC
                """)
                .fetch().all()
                .collectList()
                .flatMap(rows -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("count", rows.size(), "keys", rows)))
                .onErrorResume(ex -> {
                    log.error("ADMIN | listKeys failed | {}", ex.getMessage());
                    return errorResponse(ex.getMessage());
                });
    }

    /**
     * Creates a new API key. Body: {@code {"name":"...", "client_id":"...", "expires_at":"ISO-8601 or null"}}.
     * Returns the raw key exactly once — it is never stored and cannot be retrieved again.
     */
    public Mono<ServerResponse> createKey(ServerRequest req) {
        return req.bodyToMono(Map.class)
                .flatMap(body -> {
                    String name     = (String) body.getOrDefault("name", "unnamed");
                    String clientId = (String) body.get("client_id");
                    String rawKey   = "llm-" + UUID.randomUUID().toString().replace("-", "");
                    String hash     = sha256(rawKey);
                    Object expiresAt = body.get("expires_at");

                    return db.sql("""
                            INSERT INTO api_keys (name, key_hash, client_id, enabled, expires_at)
                            VALUES (:name, :hash, :clientId, TRUE, :expiresAt::TIMESTAMPTZ)
                            RETURNING id, name, client_id, created_at
                            """)
                            .bind("name",      name)
                            .bind("hash",      hash)
                            .bind("clientId",  clientId != null ? clientId : "")
                            .bind("expiresAt", expiresAt != null ? expiresAt.toString() : null)
                            .fetch().one()
                            .flatMap(row -> {
                                Map<String, Object> result = new LinkedHashMap<>(row);
                                result.put("raw_key", rawKey);
                                result.put("warning", "Store this key securely — it will not be shown again.");
                                log.info("ADMIN | key created | name={} | clientId={}", name, clientId);
                                return ServerResponse.status(201)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(result);
                            });
                })
                .onErrorResume(ex -> {
                    log.error("ADMIN | createKey failed | {}", ex.getMessage());
                    return errorResponse(ex.getMessage());
                });
    }

    /**
     * Enables, disables, or sets expiry on a key.
     * Body: {@code {"enabled": true|false, "expires_at": "ISO-8601 or null"}}.
     */
    public Mono<ServerResponse> updateKey(ServerRequest req) {
        long id = Long.parseLong(req.pathVariable("id"));
        return req.bodyToMono(Map.class)
                .flatMap(body -> {
                    Boolean enabled   = (Boolean) body.get("enabled");
                    Object  expiresAt = body.get("expires_at");

                    return db.sql("""
                            UPDATE api_keys SET
                                enabled    = COALESCE(:enabled, enabled),
                                expires_at = CASE WHEN :setExpiry THEN :expiresAt::TIMESTAMPTZ ELSE expires_at END
                            WHERE id = :id
                            RETURNING id, name, client_id, enabled, expires_at
                            """)
                            .bind("id",        id)
                            .bind("enabled",   enabled)
                            .bind("setExpiry", expiresAt != null)
                            .bind("expiresAt", expiresAt != null ? expiresAt.toString() : Instant.now().toString())
                            .fetch().one()
                            .flatMap(row -> {
                                log.info("ADMIN | key updated | id={} | enabled={}", id, enabled);
                                return ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(row);
                            })
                            .switchIfEmpty(errorResponse("Key not found: " + id));
                })
                .onErrorResume(ex -> {
                    log.error("ADMIN | updateKey failed | id={} | {}", id, ex.getMessage());
                    return errorResponse(ex.getMessage());
                });
    }

    public Mono<ServerResponse> deleteKey(ServerRequest req) {
        long id = Long.parseLong(req.pathVariable("id"));
        return db.sql("DELETE FROM api_keys WHERE id = :id")
                .bind("id", id)
                .then()
                .then(ServerResponse.noContent().build())
                .doOnSuccess(v -> log.info("ADMIN | key deleted | id={}", id))
                .onErrorResume(ex -> {
                    log.error("ADMIN | deleteKey failed | id={} | {}", id, ex.getMessage());
                    return errorResponse(ex.getMessage());
                });
    }

    private static Mono<ServerResponse> errorResponse(String message) {
        return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", message, "timestamp", Instant.now().toEpochMilli()));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
