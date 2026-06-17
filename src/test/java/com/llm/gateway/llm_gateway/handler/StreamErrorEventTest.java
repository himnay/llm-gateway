package com.llm.gateway.llm_gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link LlmStreamHandler.StreamErrorEvent} record and
 * its JSON serialization format.
 *
 * Verifies that SSE error payloads always contain the mandatory fields:
 * {@code type}, {@code code}, {@code message}, and {@code requestId}.
 */
class StreamErrorEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("StreamErrorEvent serialises to JSON with all required fields")
    void streamErrorEvent_serialisesToJson() throws Exception {
        LlmStreamHandler.StreamErrorEvent event =
                new LlmStreamHandler.StreamErrorEvent(
                        "error",
                        "PROVIDER_ERROR",
                        "Connection refused",
                        "req-abc-123");

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("type").asText()).isEqualTo("error");
        assertThat(node.get("code").asText()).isEqualTo("PROVIDER_ERROR");
        assertThat(node.get("message").asText()).isEqualTo("Connection refused");
        assertThat(node.get("requestId").asText()).isEqualTo("req-abc-123");
    }

    @Test
    @DisplayName("StreamErrorEvent JSON contains exactly the four expected fields")
    void streamErrorEvent_hasExactlyFourFields() throws Exception {
        LlmStreamHandler.StreamErrorEvent event =
                new LlmStreamHandler.StreamErrorEvent(
                        "error", "TIMEOUT", "Stream timed out", "req-xyz-999");

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.size()).isEqualTo(4);
        assertThat(node.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("type", "code", "message", "requestId");
    }

    @Test
    @DisplayName("StreamErrorEvent type field is always 'error'")
    void streamErrorEvent_typeIsAlwaysError() throws Exception {
        LlmStreamHandler.StreamErrorEvent event =
                new LlmStreamHandler.StreamErrorEvent(
                        "error", "GUARDRAIL_REJECTION", "Prompt blocked", "req-001");

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("type").asText()).isEqualTo("error");
        assertThat(node.get("code").asText()).isEqualTo("GUARDRAIL_REJECTION");
        assertThat(node.get("message").asText()).isEqualTo("Prompt blocked");
    }
}
