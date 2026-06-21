package com.llm.gateway.openrouter.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.llm.gateway.openrouter.dto.OpenRouterResponse;
import com.llm.gateway.openrouter.service.OpenRouterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {"gateway.auth.enabled=false", "spring.ai.openai.api-key=test-key"})
class OpenRouterControllerTest {

  @LocalServerPort private int port;

  @MockitoBean private OpenRouterService openRouterService;

  private WebTestClient client() {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port + "/openrouter/v1")
        .build();
  }

  @DisplayName("POST /chat returns the service response with an echoed X-Request-ID")
  @Test
  void chatReturnsServiceResponse() {
    when(openRouterService.chat(any()))
        .thenReturn(
            OpenRouterResponse.builder()
                .content("Hello!")
                .model("openai/gpt-4o")
                .provider("openrouter")
                .build());

    client()
        .post()
        .uri("/chat")
        .header("X-Request-ID", "test-cid")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(java.util.Map.of("prompt", "Hi there"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Request-ID", "test-cid")
        .expectBody(OpenRouterResponse.class)
        .value(
            body ->
                org.assertj.core.api.Assertions.assertThat(body.getContent()).isEqualTo("Hello!"));
  }

  @DisplayName("POST /chat with a blank prompt returns 400")
  @Test
  void chatRejectsBlankPrompt() {
    client()
        .post()
        .uri("/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(java.util.Map.of("prompt", ""))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
