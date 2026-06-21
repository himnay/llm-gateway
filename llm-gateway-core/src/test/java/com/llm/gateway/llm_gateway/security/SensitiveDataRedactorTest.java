package com.llm.gateway.llm_gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.llm.gateway.llm_gateway.config.GuardrailPatternProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

  // Construct with the default externalised pattern catalogue
  private final SensitiveDataRedactor redactor =
      new SensitiveDataRedactor(new GuardrailPatternProperties());

  @DisplayName("redacts e-mail addresses")
  @Test
  void redactsEmail() {
    var r = redactor.redact("contact me at alice@example.com please");
    assertThat(r.redacted()).isTrue();
    assertThat(r.text()).doesNotContain("alice@example.com").contains("[EMAIL]");
    assertThat(r.types()).contains("[EMAIL]");
  }

  @DisplayName("redacts API keys / secrets so they never reach the LLM or logs")
  @Test
  void redactsApiKey() {
    var r = redactor.redact("here is my key sk-abc123DEF456ghi789JKL0 use it");
    assertThat(r.redacted()).isTrue();
    assertThat(r.text()).doesNotContain("sk-abc123DEF456ghi789JKL0").contains("[API_KEY]");
  }

  @DisplayName("redacts credit-card and SSN numbers")
  @Test
  void redactsFinancialPii() {
    var r = redactor.redact("card 4111111111111111 ssn 123-45-6789");
    assertThat(r.text()).contains("[CREDIT_CARD]").contains("[SSN]");
    assertThat(r.types()).contains("[CREDIT_CARD]", "[SSN]");
  }

  @DisplayName("redacts AWS access keys and bearer tokens")
  @Test
  void redactsCloudSecrets() {
    var r =
        redactor.redact("AKIAIOSFODNN7EXAMPLE and Authorization: Bearer abcdEFGH1234ijklMNOP5678");
    assertThat(r.text()).contains("[AWS_KEY]").contains("[BEARER_TOKEN]");
  }

  @DisplayName("leaves clean text untouched")
  @Test
  void leavesCleanText() {
    var r = redactor.redact("What is the capital of France?");
    assertThat(r.redacted()).isFalse();
    assertThat(r.text()).isEqualTo("What is the capital of France?");
    assertThat(r.types()).isEmpty();
  }

  @DisplayName("detect() flags categories without modifying the text")
  @Test
  void detectsWithoutModifying() {
    var types = redactor.detect("reach me on bob@corp.io or 555-123-4567");
    assertThat(types).contains("[EMAIL]", "[PHONE]");
  }

  @DisplayName("null / blank input is handled safely")
  @Test
  void handlesNullAndBlank() {
    assertThat(redactor.redact(null).redacted()).isFalse();
    assertThat(redactor.redact("   ").redacted()).isFalse();
    assertThat(redactor.detect(null)).isEmpty();
  }
}
