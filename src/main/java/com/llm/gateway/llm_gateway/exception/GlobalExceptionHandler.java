package com.llm.gateway.llm_gateway.exception;

import com.llm.gateway.llm_gateway.security.PromptValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralised exception handler for the LLM Gateway.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles prompt injection / validation failures → HTTP 400.
     */
    @ExceptionHandler(PromptValidationException.class)
    public ResponseEntity<Map<String, Object>> handlePromptValidation(PromptValidationException ex) {
        log.warn("SECURITY | Prompt rejected | violations={}", ex.getViolations());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, "Prompt validation failed", ex.getViolations()));
    }

    /**
     * Handles unknown / misconfigured provider names → HTTP 400.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request | {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, ex.getMessage(), List.of()));
    }

    /**
     * Catch-all for unhandled runtime exceptions → HTTP 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR,
                        "An internal error occurred. Please try again.", List.of()));
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> errorBody(HttpStatus status, String message, List<String> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",    status.value());
        body.put("error",     status.getReasonPhrase());
        body.put("message",   message);
        body.put("details",   details);
        body.put("timestamp", Instant.now().toEpochMilli());
        return body;
    }
}

