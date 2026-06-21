package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.config.GuardrailPatternProperties;
import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * INPUT + OUTPUT guardrail — harmful content detection.
 *
 * <p>INPUT : blocks the request if a toxic pattern is matched (→ HTTP 400). OUTPUT : async scan;
 * logs warning and emits metric if harmful content found in response.
 *
 * <p>NOTE: Keyword-list detection produces false positives on legitimate security research and
 * educational queries. For production, augment with an ML-based classifier (e.g. OpenAI Moderation
 * API, Perspective API, or Azure AI Content Safety).
 */
@Slf4j
@Component
public class ToxicityFilterAdvisor implements BaseAdvisor {

  // Toxic keyword list is externalised in GuardrailPatternProperties
  // (llm.guardrails.patterns.toxic-keywords) so it can be tuned without code changes.
  private final List<String> toxicPatterns;
  private final LlmMetricsService metricsService;

  @Value("${llm.guardrails.toxicity.enabled:true}")
  private boolean enabled;

  public ToxicityFilterAdvisor(
      LlmMetricsService metricsService, GuardrailPatternProperties patterns) {
    this.metricsService = metricsService;
    this.toxicPatterns = patterns.getToxicKeywords().stream().map(String::toLowerCase).toList();
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    if (!enabled) return request;

    String text = AdvisorUtils.extractUserText(request);
    if (text == null || text.isBlank()) return request;

    String lower = text.toLowerCase();
    for (String pattern : toxicPatterns) {
      if (lower.contains(pattern)) {
        log.warn("GUARDRAIL | TOXIC_INPUT_BLOCKED | pattern={}", pattern);
        metricsService.recordRejectedRequest(
            (String) request.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown"),
            "TOXIC_CONTENT");
        throw new PromptValidationException(
            List.of("Request blocked: harmful or toxic content detected"));
      }
    }
    return request;
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    if (!enabled) return response;

    String text = AdvisorUtils.extractResponseText(response);
    if (text == null || text.isBlank()) return response;

    String provider =
        (String) response.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown");
    Mono.fromRunnable(() -> scanOutput(text, provider))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(null, ex -> log.debug("ToxicityFilter | scan error | {}", ex.getMessage()));

    return response;
  }

  private void scanOutput(String text, String provider) {
    String lower = text.toLowerCase();
    for (String pattern : toxicPatterns) {
      if (lower.contains(pattern)) {
        log.warn("GUARDRAIL | TOXIC_OUTPUT_DETECTED | provider={} | pattern={}", provider, pattern);
        metricsService.recordError(provider, "TOXIC_OUTPUT_DETECTED");
        return;
      }
    }
  }
}
