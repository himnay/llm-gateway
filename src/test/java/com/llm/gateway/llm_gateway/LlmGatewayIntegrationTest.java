package com.llm.gateway.llm_gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.llm.gateway.llm_gateway.audit.RequestLogService;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.facade.LlmGatewayFacade;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@TestPropertySource(
    properties = {
      "gateway.auth.enabled=false",
      "gateway.rate-limiter.enabled=false",
      "spring.flyway.enabled=false",
      "spring.ai.openai.api-key=test-key",
      "spring.ai.anthropic.api-key=test-key",
      "llm.external.guardrails.enabled=false",
      "llm.guardrails.external.enabled=false"
    })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class LlmGatewayIntegrationTest {

  @LocalServerPort private int port;

  private WebTestClient webTestClient;

  @MockitoBean private LlmGatewayFacade facade;

  @MockitoBean private RequestLogService requestLogService;

  private static final LlmResponse SUCCESS_RESPONSE =
      LlmResponse.builder()
          .provider("openai")
          .content("Hello from OpenAI")
          .requestId("test-req-1")
          .timestamp(System.currentTimeMillis())
          .build();

  private static final LlmResponse ERROR_RESPONSE =
      LlmResponse.builder()
          .provider("openai")
          .error("Provider unavailable")
          .timestamp(System.currentTimeMillis())
          .build();

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/llm/v1").build();
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Health & Info
  // ─────────────────────────────────────────────────────────────────────────

  @DisplayName("GET /health returns 200 UP")
  @Test
  void health_returns200() {
    webTestClient
        .get()
        .uri("/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .value(body -> assertThat(body.get("status")).isEqualTo("UP"));
  }

  @DisplayName("GET /models returns provider model catalog")
  @Test
  void models_returnsAllProviders() {
    webTestClient
        .get()
        .uri("/models")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .value(
            body -> {
              assertThat(body).containsKeys("openai", "anthropic", "ollama", "google");
            });
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Unified query endpoint
  // ─────────────────────────────────────────────────────────────────────────

  @DisplayName("POST /query routes to openai by default")
  @Test
  void query_defaultsToOpenAi() {
    // /query goes through auto-failover (silently routes to the next provider on auth/config
    // errors)
    when(facade.executeWithAutoFailover(eq("openai"), any())).thenReturn(SUCCESS_RESPONSE);

    webTestClient
        .post()
        .uri("/query")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "What is Spring AI?"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LlmResponse.class)
        .value(
            r -> {
              assertThat(r.getContent()).isEqualTo("Hello from OpenAI");
              assertThat(r.getError()).isNull();
            });

    verify(facade).executeWithAutoFailover(eq("openai"), any());
  }

  @DisplayName("POST /query uses provider from request body")
  @Test
  void query_usesRequestBodyProvider() {
    LlmResponse anthropicResponse =
        LlmResponse.builder()
            .provider("anthropic")
            .content("Hello from Anthropic")
            .timestamp(System.currentTimeMillis())
            .build();
    when(facade.executeWithAutoFailover(eq("anthropic"), any())).thenReturn(anthropicResponse);

    webTestClient
        .post()
        .uri("/query")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "Hello", "provider", "anthropic"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LlmResponse.class)
        .value(r -> assertThat(r.getContent()).isEqualTo("Hello from Anthropic"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Per-provider dynamic route
  // ─────────────────────────────────────────────────────────────────────────

  @DisplayName("POST /{provider}/chat routes to correct provider")
  @Test
  void perProviderChat_routesCorrectly() {
    when(facade.execute(eq("ollama"), any()))
        .thenReturn(
            LlmResponse.builder()
                .provider("ollama")
                .content("Ollama reply")
                .timestamp(System.currentTimeMillis())
                .build());

    webTestClient
        .post()
        .uri("/ollama/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "Hi Ollama"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LlmResponse.class)
        .value(r -> assertThat(r.getProvider()).isEqualTo("ollama"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Failover endpoint
  // ─────────────────────────────────────────────────────────────────────────

  @DisplayName("POST /failover succeeds on first provider")
  @Test
  void failover_succeedsOnFirstProvider() {
    when(facade.executeWithFailover(anyList(), any())).thenReturn(SUCCESS_RESPONSE);

    webTestClient
        .post()
        .uri("/failover")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "Hello", "providers", List.of("openai", "anthropic", "ollama")))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LlmResponse.class)
        .value(r -> assertThat(r.getError()).isNull());
  }

  @DisplayName("POST /failover uses default chain when providers not specified")
  @Test
  void failover_usesDefaultChainWhenNoProviders() {
    when(facade.executeWithFailover(eq(List.of("openai", "anthropic", "ollama")), any()))
        .thenReturn(SUCCESS_RESPONSE);

    webTestClient
        .post()
        .uri("/failover")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "Hello"))
        .exchange()
        .expectStatus()
        .isOk();

    verify(facade).executeWithFailover(eq(List.of("openai", "anthropic", "ollama")), any());
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Multi-turn chat
  // ─────────────────────────────────────────────────────────────────────────

  @DisplayName("POST /chat rejects request without session_id")
  @Test
  void chat_rejects_missingSessionId() {
    webTestClient
        .post()
        .uri("/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "Hello"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(Map.class)
        .value(body -> assertThat(body.get("error").toString()).contains("session_id"));

    verifyNoInteractions(facade);
  }

  @DisplayName("POST /chat with session_id delegates to facade")
  @Test
  void chat_withSessionId_delegatesToFacade() {
    when(facade.executeWithAutoFailover(eq("openai"), any())).thenReturn(SUCCESS_RESPONSE);

    webTestClient
        .post()
        .uri("/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "What is 2+2?", "session_id", "session-abc"))
        .exchange()
        .expectStatus()
        .isOk();

    verify(facade)
        .executeWithAutoFailover(
            eq("openai"), argThat(r -> "session-abc".equals(r.getSessionId())));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Error handling
  // ─────────────────────────────────────────────────────────────────────────

  @DisplayName("Unknown provider returns 400 bad request")
  @Test
  void unknownProvider_returns400() {
    when(facade.execute(eq("nonexistent"), any()))
        .thenThrow(new IllegalArgumentException("Unknown LLM provider: 'nonexistent'"));

    webTestClient
        .post()
        .uri("/nonexistent/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "Hello"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(Map.class)
        .value(body -> assertThat(body.get("error").toString()).contains("nonexistent"));
  }

  @DisplayName("Internal server error returns 500")
  @Test
  void internalError_returns500() {
    when(facade.executeWithAutoFailover(any(), any()))
        .thenThrow(new RuntimeException("Unexpected failure"));

    webTestClient
        .post()
        .uri("/query")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("prompt", "Hello"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
