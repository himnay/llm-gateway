package com.llm.gateway.llm_gateway.guardrail.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.guardrail.remote.GuardrailValidationResult;
import com.llm.gateway.llm_gateway.guardrail.remote.RemoteGuardrailClient;
import com.llm.gateway.llm_gateway.guardrail.remote.RemoteGuardrailProperties;
import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RemoteGuardrailStepTest {

  private RemoteGuardrailClient client;
  private LlmMetricsService metricsService;
  private RemoteGuardrailProperties properties;
  private RemoteGuardrailStep step;

  @BeforeEach
  void setUp() {
    client = mock(RemoteGuardrailClient.class);
    metricsService = mock(LlmMetricsService.class);
    properties = new RemoteGuardrailProperties();
    step = new RemoteGuardrailStep(client, properties, metricsService);
  }

  private static GuardrailContext contextFor(String prompt) {
    LlmRequest request = new LlmRequest();
    request.setPrompt(prompt);
    return new GuardrailContext("openai", "req-1", request);
  }

  @DisplayName("passing validation lets the request continue unchanged")
  @Test
  void passingValidationContinues() {
    when(client.validate(anyString(), eq("input")))
        .thenReturn(GuardrailValidationResult.passedResult());
    GuardrailContext context = contextFor("safe prompt");

    step.apply(context);

    assertThat(context.getPrompt()).isEqualTo("safe prompt");
    assertThat(context.isPromptModified()).isFalse();
  }

  @DisplayName("failed validation rejects the request with the sidecar's violations")
  @Test
  void failedValidationRejects() {
    when(client.validate(anyString(), eq("input")))
        .thenReturn(
            new GuardrailValidationResult(
                false, List.of("prompt-injection: matched 'jailbreak'"), null, 0.5));

    assertThatThrownBy(() -> step.apply(contextFor("jailbreak please")))
        .isInstanceOf(PromptValidationException.class)
        .hasMessageContaining("prompt-injection");

    verify(metricsService).recordRejectedRequest("openai", "EXTERNAL_GUARDRAIL");
  }

  @DisplayName("sanitized text from the sidecar replaces the prompt")
  @Test
  void sanitizedTextReplacesPrompt() {
    when(client.validate(anyString(), eq("input")))
        .thenReturn(new GuardrailValidationResult(true, List.of(), "my email is [EMAIL]", 0.0));
    GuardrailContext context = contextFor("my email is a@b.com");

    step.apply(context);

    assertThat(context.getPrompt()).isEqualTo("my email is [EMAIL]");
    assertThat(context.isPromptModified()).isTrue();
  }

  @DisplayName("disabled step never calls the sidecar")
  @Test
  void disabledStepSkipsCall() {
    properties.setEnabled(false);

    step.apply(contextFor("anything"));

    verifyNoInteractions(client);
  }
}
