package com.llm.gateway.llm_gateway.security;

import com.llm.gateway.llm_gateway.TestcontainersConfiguration;
import com.llm.gateway.llm_gateway.audit.RequestLogService;
import com.llm.gateway.llm_gateway.facade.LlmGatewayFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * With OAuth2 enabled: read-only infrastructure and the public info/docs routes need no token,
 * while writable or sensitive actuator endpoints do. Security matchers see the path inside the
 * /llm/v1 base path, which is why these routes are requested with the prefix.
 */
@TestPropertySource(
    properties = {
      "gateway.auth.enabled=true",
      "gateway.rate-limiter.enabled=false",
      "spring.flyway.enabled=false",
      "spring.ai.openai.api-key=test-key",
      "spring.ai.anthropic.api-key=test-key",
      "llm.external.guardrails.enabled=false",
      "llm.guardrails.external.enabled=false",
      "management.endpoint.health.group.readiness.include=readinessState"
    })
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorSecurityIntegrationTest {

  @LocalServerPort private int port;

  @MockitoBean private LlmGatewayFacade facade;

  @MockitoBean private RequestLogService requestLogService;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/llm/v1").build();
  }

  @ParameterizedTest
  @DisplayName("Writable or sensitive actuator endpoints require a token")
  @ValueSource(strings = {"/actuator/loggers", "/actuator/env", "/actuator/circuitbreakers"})
  void sensitiveActuatorEndpointsRequireAToken(String path) {
    client.get().uri(path).exchange().expectStatus().isUnauthorized();
  }

  @ParameterizedTest
  @DisplayName("Probes, Prometheus and the public info routes need no token")
  @ValueSource(
      strings = {"/actuator/health/liveness", "/actuator/prometheus", "/providers", "/models"})
  void publicRoutesNeedNoToken(String path) {
    client.get().uri(path).exchange().expectStatus().isOk();
  }

  @ParameterizedTest
  @DisplayName("Swagger UI is reachable without a token")
  @ValueSource(strings = {"/swagger-ui.html", "/api-docs/swagger-config"})
  void swaggerUiNeedsNoToken(String path) {
    client
        .get()
        .uri(path)
        .exchange()
        .expectStatus()
        .value(
            status ->
                org.assertj.core.api.Assertions.assertThat(status)
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
  }
}
