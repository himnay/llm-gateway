package com.llm.gateway.llm_gateway.security;

import com.llm.gateway.llm_gateway.config.GuardrailPatternProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    // Construct with the default externalised pattern catalogue
    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor(new GuardrailPatternProperties());

    @Test
    @DisplayName("redacts e-mail addresses")
    void redactsEmail() {
        var r = redactor.redact("contact me at alice@example.com please");
        assertThat(r.redacted()).isTrue();
        assertThat(r.text()).doesNotContain("alice@example.com").contains("[EMAIL]");
        assertThat(r.types()).contains("[EMAIL]");
    }

    @Test
    @DisplayName("redacts API keys / secrets so they never reach the LLM or logs")
    void redactsApiKey() {
        var r = redactor.redact("here is my key sk-abc123DEF456ghi789JKL0 use it");
        assertThat(r.redacted()).isTrue();
        assertThat(r.text()).doesNotContain("sk-abc123DEF456ghi789JKL0").contains("[API_KEY]");
    }

    @Test
    @DisplayName("redacts credit-card and SSN numbers")
    void redactsFinancialPii() {
        var r = redactor.redact("card 4111111111111111 ssn 123-45-6789");
        assertThat(r.text()).contains("[CREDIT_CARD]").contains("[SSN]");
        assertThat(r.types()).contains("[CREDIT_CARD]", "[SSN]");
    }

    @Test
    @DisplayName("redacts AWS access keys and bearer tokens")
    void redactsCloudSecrets() {
        var r = redactor.redact("AKIAIOSFODNN7EXAMPLE and Authorization: Bearer abcdEFGH1234ijklMNOP5678");
        assertThat(r.text()).contains("[AWS_KEY]").contains("[BEARER_TOKEN]");
    }

    @Test
    @DisplayName("leaves clean text untouched")
    void leavesCleanText() {
        var r = redactor.redact("What is the capital of France?");
        assertThat(r.redacted()).isFalse();
        assertThat(r.text()).isEqualTo("What is the capital of France?");
        assertThat(r.types()).isEmpty();
    }

    @Test
    @DisplayName("detect() flags categories without modifying the text")
    void detectsWithoutModifying() {
        var types = redactor.detect("reach me on bob@corp.io or 555-123-4567");
        assertThat(types).contains("[EMAIL]", "[PHONE]");
    }

    @Test
    @DisplayName("null / blank input is handled safely")
    void handlesNullAndBlank() {
        assertThat(redactor.redact(null).redacted()).isFalse();
        assertThat(redactor.redact("   ").redacted()).isFalse();
        assertThat(redactor.detect(null)).isEmpty();
    }
}
