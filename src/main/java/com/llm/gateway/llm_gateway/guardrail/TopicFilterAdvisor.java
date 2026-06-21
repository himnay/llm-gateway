package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * INPUT guardrail — configurable topic restriction. Configure via
 * llm.guardrails.topic-filter.blocked-topics (comma-separated).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicFilterAdvisor implements BaseAdvisor {

  private final LlmMetricsService metricsService;

  @Value("${llm.guardrails.topic-filter.enabled:true}")
  private boolean enabled;

  @Value("${llm.guardrails.topic-filter.blocked-topics:}")
  private List<String> blockedTopics;

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 3;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    if (!enabled || blockedTopics == null || blockedTopics.isEmpty()) return request;

    String text = AdvisorUtils.extractUserText(request);
    if (text == null || text.isBlank()) return request;

    String lower = text.toLowerCase();
    for (String topic : blockedTopics) {
      String t = topic.trim().toLowerCase();
      if (!t.isBlank() && lower.contains(t)) {
        log.warn("GUARDRAIL | TOPIC_BLOCKED | topic={}", t);
        metricsService.recordRejectedRequest(
            (String) request.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown"),
            "RESTRICTED_TOPIC");
        throw new PromptValidationException(
            List.of("Request blocked: topic '" + t + "' is restricted"));
      }
    }
    return request;
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }
}
