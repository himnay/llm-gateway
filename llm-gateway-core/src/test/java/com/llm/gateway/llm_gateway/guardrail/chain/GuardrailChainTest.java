package com.llm.gateway.llm_gateway.guardrail.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.guardrail.event.GuardrailViolationEvent;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class GuardrailChainTest {

  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

  private static GuardrailContext contextFor(String prompt) {
    LlmRequest request = new LlmRequest();
    request.setPrompt(prompt);
    return new GuardrailContext("openai", "req-1", request);
  }

  private static GuardrailStep step(String name, int order, List<String> callLog) {
    return new GuardrailStep() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int getOrder() {
        return order;
      }

      @Override
      public void apply(GuardrailContext context) {
        callLog.add(name);
      }
    };
  }

  @Test
  @DisplayName("applies every step in list order")
  void appliesStepsInOrder() {
    List<String> callLog = new ArrayList<>();
    GuardrailChain chain =
        new GuardrailChain(
            List.of(step("first", 100, callLog), step("second", 200, callLog)), publisher);

    chain.apply(contextFor("hello"));

    assertThat(callLog).containsExactly("first", "second");
  }

  @Test
  @DisplayName("a rewriting step marks the prompt as modified")
  void rewritingStepMarksPromptModified() {
    GuardrailStep rewriter =
        new GuardrailStep() {
          @Override
          public String name() {
            return "rewriter";
          }

          @Override
          public int getOrder() {
            return 100;
          }

          @Override
          public void apply(GuardrailContext context) {
            context.updatePrompt("clean");
          }
        };
    GuardrailContext context = contextFor("dirty");

    new GuardrailChain(List.of(rewriter), publisher).apply(context);

    assertThat(context.getPrompt()).isEqualTo("clean");
    assertThat(context.isPromptModified()).isTrue();
  }

  @Test
  @DisplayName("a rejecting step stops the chain and publishes a violation event")
  void rejectionStopsChainAndPublishesEvent() {
    List<String> callLog = new ArrayList<>();
    GuardrailStep rejecting =
        new GuardrailStep() {
          @Override
          public String name() {
            return "rejecting";
          }

          @Override
          public int getOrder() {
            return 100;
          }

          @Override
          public void apply(GuardrailContext context) {
            throw new PromptValidationException(List.of("blocked"));
          }
        };
    GuardrailChain chain =
        new GuardrailChain(List.of(rejecting, step("never-reached", 200, callLog)), publisher);

    assertThatThrownBy(() -> chain.apply(contextFor("bad prompt")))
        .isInstanceOf(PromptValidationException.class);

    assertThat(callLog).isEmpty();
    verify(publisher).publishEvent(any(GuardrailViolationEvent.class));
  }
}
