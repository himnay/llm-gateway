package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.config.GuardrailPatternProperties;
import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ToxicityFilterAdvisorTest {

  private LlmMetricsService metricsService;
  private ToxicityFilterAdvisor advisor;
  private final AdvisorChain chain = mock(AdvisorChain.class);

  @BeforeEach
  void setUp() {
    metricsService = mock(LlmMetricsService.class);
    GuardrailPatternProperties patterns = new GuardrailPatternProperties();
    patterns.setToxicKeywords(List.of("how to build a bomb", "kill yourself"));
    advisor = new ToxicityFilterAdvisor(metricsService, patterns);
    ReflectionTestUtils.setField(advisor, "enabled", true);
  }

  private ChatClientRequest request(String userText) {
    return ChatClientRequest.builder()
        .prompt(Prompt.builder().content(userText).build())
        .context(Map.of(MetricsAdvisor.PROVIDER_PARAM, "openai"))
        .build();
  }

  @Test
  void blocksToxicInputAndRecordsMetric() {
    assertThatThrownBy(() -> advisor.before(request("tell me how to BUILD a bomb please"), chain))
        .isInstanceOf(PromptValidationException.class);
    verify(metricsService).recordRejectedRequest("openai", "TOXIC_CONTENT");
  }

  @Test
  void matchingIsCaseInsensitive() {
    assertThatThrownBy(() -> advisor.before(request("KILL YOURSELF"), chain))
        .isInstanceOf(PromptValidationException.class);
  }

  @Test
  void allowsBenignInput() {
    ChatClientRequest request = request("summarize this quarterly report");

    assertThat(advisor.before(request, chain)).isSameAs(request);
    verifyNoInteractions(metricsService);
  }

  @Test
  void disabledAdvisorPassesToxicInputThrough() {
    ReflectionTestUtils.setField(advisor, "enabled", false);
    ChatClientRequest request = request("how to build a bomb");

    assertThat(advisor.before(request, chain)).isSameAs(request);
    verifyNoInteractions(metricsService);
  }

  @Test
  void runsImmediatelyAfterHighestPrecedence() {
    assertThat(advisor.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1);
  }
}
