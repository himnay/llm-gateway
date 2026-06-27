package com.llm.gateway.llm_gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LlmMetricsService} — verifies that the new error, failover, token-usage and
 * guardrail-rejection counters are correctly registered and incremented in the {@link
 * MeterRegistry}.
 */
class LlmMetricsServiceTest {

  private MeterRegistry registry;
  private LlmMetricsService metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new LlmMetricsService(registry);
    // Trigger @PostConstruct pre-registration
    metrics.preRegister();
  }

  // ── recordProviderError ───────────────────────────────────────────────────

  @Test
  @DisplayName("recordProviderError increments llm.provider.error.total with correct tags")
  void recordProviderError_incrementsCounter() {
    metrics.recordProviderError("openai", "TIMEOUT");
    metrics.recordProviderError("openai", "TIMEOUT");
    metrics.recordProviderError("anthropic", "AUTH_ERROR");

    Counter openaiTimeout =
        registry
            .find("llm.provider.error.total")
            .tag("provider", "openai")
            .tag("error_type", "TIMEOUT")
            .counter();
    assertThat(openaiTimeout).isNotNull();
    assertThat(openaiTimeout.count()).isEqualTo(2.0);

    Counter anthropicAuth =
        registry
            .find("llm.provider.error.total")
            .tag("provider", "anthropic")
            .tag("error_type", "AUTH_ERROR")
            .counter();
    assertThat(anthropicAuth).isNotNull();
    assertThat(anthropicAuth.count()).isEqualTo(1.0);
  }

  // ── recordFailover ────────────────────────────────────────────────────────

  @Test
  @DisplayName("recordFailover increments llm.provider.failover.total with from/to tags")
  void recordFailover_incrementsCounter() {
    metrics.recordFailover("openai", "anthropic");
    metrics.recordFailover("openai", "anthropic");
    metrics.recordFailover("anthropic", "ollama");

    Counter openaiToAnthropic =
        registry
            .find("llm.provider.failover.total")
            .tag("from", "openai")
            .tag("to", "anthropic")
            .counter();
    assertThat(openaiToAnthropic).isNotNull();
    assertThat(openaiToAnthropic.count()).isEqualTo(2.0);

    Counter anthropicToOllama =
        registry
            .find("llm.provider.failover.total")
            .tag("from", "anthropic")
            .tag("to", "ollama")
            .counter();
    assertThat(anthropicToOllama).isNotNull();
    assertThat(anthropicToOllama.count()).isEqualTo(1.0);
  }

  // ── recordTokenUsage (simplified signature) ───────────────────────────────

  @Test
  @DisplayName("recordTokenUsage registers prompt and completion counters")
  void recordTokenUsage_registersPromptAndCompletionCounters() {
    metrics.recordTokenUsage("openai", 150, 300);

    Counter promptCounter =
        registry.find("llm.token.usage").tag("provider", "openai").tag("type", "prompt").counter();
    assertThat(promptCounter).isNotNull();
    assertThat(promptCounter.count()).isEqualTo(150.0);

    Counter completionCounter =
        registry
            .find("llm.token.usage")
            .tag("provider", "openai")
            .tag("type", "completion")
            .counter();
    assertThat(completionCounter).isNotNull();
    assertThat(completionCounter.count()).isEqualTo(300.0);
  }

  @Test
  @DisplayName("recordTokenUsage skips zero token counts")
  void recordTokenUsage_skipsZeroCounts() {
    metrics.recordTokenUsage("ollama", 0, 0);

    Counter promptCounter =
        registry.find("llm.token.usage").tag("provider", "ollama").tag("type", "prompt").counter();
    // Counter should not be registered at all for zero values
    assertThat(promptCounter).isNull();
  }

  // ── recordGuardrailRejection ──────────────────────────────────────────────

  @Test
  @DisplayName("recordGuardrailRejection increments llm.guardrail.rejection.total with reason tag")
  void recordGuardrailRejection_incrementsCounter() {
    metrics.recordGuardrailRejection("PROMPT_INJECTION");
    metrics.recordGuardrailRejection("PROMPT_INJECTION");
    metrics.recordGuardrailRejection("PII_DETECTED");

    Counter injectionCounter =
        registry.find("llm.guardrail.rejection.total").tag("reason", "PROMPT_INJECTION").counter();
    assertThat(injectionCounter).isNotNull();
    assertThat(injectionCounter.count()).isEqualTo(2.0);

    Counter piiCounter =
        registry.find("llm.guardrail.rejection.total").tag("reason", "PII_DETECTED").counter();
    assertThat(piiCounter).isNotNull();
    assertThat(piiCounter.count()).isEqualTo(1.0);
  }
}
