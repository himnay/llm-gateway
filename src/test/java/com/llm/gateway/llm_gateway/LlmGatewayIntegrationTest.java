package com.llm.gateway.llm_gateway;

import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmGatewayFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = {
        "gateway.rate-limiter.enabled=false",    // disable Redis filter in tests
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmGatewayIntegrationTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @MockitoBean
    private LlmGatewayFacade facade;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    private ReactiveValueOperations<String, String> valueOps;

    private static final LlmResponse SUCCESS_RESPONSE = LlmResponse.builder()
            .provider("openai")
            .content("Hello from OpenAI")
            .requestId("test-req-1")
            .timestamp(System.currentTimeMillis())
            .build();

    private static final LlmResponse ERROR_RESPONSE = LlmResponse.builder()
            .provider("openai")
            .error("Provider unavailable")
            .timestamp(System.currentTimeMillis())
            .build();

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/llm")
                .build();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Health & Info
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /health returns 200 UP")
    void health_returns200() {
        webTestClient.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(body -> assertThat(body.get("status")).isEqualTo("UP"));
    }

    @Test
    @DisplayName("GET /models returns provider model catalog")
    void models_returnsAllProviders() {
        webTestClient.get().uri("/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(body -> {
                    assertThat(body).containsKeys("openai", "anthropic", "ollama", "google");
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unified query endpoint
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /query routes to openai by default")
    void query_defaultsToOpenAi() {
        when(facade.execute(eq("openai"), any())).thenReturn(SUCCESS_RESPONSE);

        webTestClient.post().uri("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "What is Spring AI?"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LlmResponse.class)
                .value(r -> {
                    assertThat(r.getContent()).isEqualTo("Hello from OpenAI");
                    assertThat(r.getError()).isNull();
                });

        verify(facade).execute(eq("openai"), any());
    }

    @Test
    @DisplayName("POST /query uses provider from request body")
    void query_usesRequestBodyProvider() {
        LlmResponse anthropicResponse = LlmResponse.builder()
                .provider("anthropic").content("Hello from Anthropic")
                .timestamp(System.currentTimeMillis()).build();
        when(facade.execute(eq("anthropic"), any())).thenReturn(anthropicResponse);

        webTestClient.post().uri("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "Hello", "provider", "anthropic"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LlmResponse.class)
                .value(r -> assertThat(r.getContent()).isEqualTo("Hello from Anthropic"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-provider dynamic route
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /{provider}/chat routes to correct provider")
    void perProviderChat_routesCorrectly() {
        when(facade.execute(eq("ollama"), any())).thenReturn(
                LlmResponse.builder().provider("ollama").content("Ollama reply")
                        .timestamp(System.currentTimeMillis()).build());

        webTestClient.post().uri("/ollama/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "Hi Ollama"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LlmResponse.class)
                .value(r -> assertThat(r.getProvider()).isEqualTo("ollama"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Failover endpoint
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /failover succeeds on first provider")
    void failover_succeedsOnFirstProvider() {
        when(facade.executeWithFailover(anyList(), any())).thenReturn(SUCCESS_RESPONSE);

        webTestClient.post().uri("/failover")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "Hello", "providers", List.of("openai", "anthropic", "ollama")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LlmResponse.class)
                .value(r -> assertThat(r.getError()).isNull());
    }

    @Test
    @DisplayName("POST /failover uses default chain when providers not specified")
    void failover_usesDefaultChainWhenNoProviders() {
        when(facade.executeWithFailover(
                eq(List.of("openai", "anthropic", "ollama")), any()))
                .thenReturn(SUCCESS_RESPONSE);

        webTestClient.post().uri("/failover")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "Hello"))
                .exchange()
                .expectStatus().isOk();

        verify(facade).executeWithFailover(eq(List.of("openai", "anthropic", "ollama")), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multi-turn chat
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /chat rejects request without session_id")
    void chat_rejects_missingSessionId() {
        webTestClient.post().uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "Hello"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Map.class)
                .value(body -> assertThat(body.get("error").toString())
                        .contains("session_id"));

        verifyNoInteractions(facade);
    }

    @Test
    @DisplayName("POST /chat with session_id delegates to facade")
    void chat_withSessionId_delegatesToFacade() {
        when(facade.execute(eq("openai"), any())).thenReturn(SUCCESS_RESPONSE);

        webTestClient.post().uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "What is 2+2?", "session_id", "session-abc"))
                .exchange()
                .expectStatus().isOk();

        verify(facade).execute(eq("openai"), argThat(r -> "session-abc".equals(r.getSessionId())));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown provider returns 400 bad request")
    void unknownProvider_returns400() {
        when(facade.execute(eq("nonexistent"), any()))
                .thenThrow(new IllegalArgumentException("Unknown LLM provider: 'nonexistent'"));

        webTestClient.post().uri("/nonexistent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "Hello"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Map.class)
                .value(body -> assertThat(body.get("error").toString()).contains("nonexistent"));
    }

    @Test
    @DisplayName("Internal server error returns 500")
    void internalError_returns500() {
        when(facade.execute(any(), any()))
                .thenThrow(new RuntimeException("Unexpected failure"));

        webTestClient.post().uri("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "Hello"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
