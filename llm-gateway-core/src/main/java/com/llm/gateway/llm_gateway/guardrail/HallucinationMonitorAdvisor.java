package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OUTPUT guardrail — heuristic hallucination detection.
 *
 * <p>When {@code llm.guardrails.hallucination.block-on-suspicion=false} (default): check runs
 * fire-and-forget after the response is returned — zero added latency. A WARN is logged and a
 * {@code hallucination_suspected=true} flag is added to the response context.
 *
 * <p>When {@code block-on-suspicion=true}: check runs synchronously; responses exceeding the
 * threshold are replaced with the configured {@code block-message} and the context flag
 * {@code hallucination_blocked=true} is set.
 *
 * <p>NOTE: This is a heuristic signal (uncertainty phrases + knowledge-cutoff signals). It does not
 * detect all hallucinations and produces false positives for legitimate epistemic hedging. For
 * precise factual grounding, consider RAG or dedicated fact-checking services.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HallucinationMonitorAdvisor implements BaseAdvisor {

  static final String CTX_SUSPECTED = "hallucination_suspected";
  static final String CTX_BLOCKED   = "hallucination_blocked";

  private static final List<String> UNCERTAINTY =
      List.of(
          "i'm not sure",
          "i think",
          "i believe",
          "probably",
          "might be",
          "i cannot confirm",
          "i'm not certain",
          "i may be wrong",
          "to the best of my knowledge",
          "as far as i know",
          "i'm unsure");

  private static final List<String> SIGNALS =
      List.of(
          "my knowledge cutoff",
          "i don't have access to real-time",
          "as of my last update",
          "i cannot access current",
          "please verify",
          "you should double-check",
          "i cannot guarantee accuracy",
          "this may not be accurate",
          "i don't have real-time information");

  private final LlmMetricsService metricsService;
  private final HallucinationGuardProperties properties;

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 10;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    return request;
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    if (!properties.isEnabled()) return response;

    String text = AdvisorUtils.extractResponseText(response);
    if (text == null || text.isBlank()) return response;

    String provider =
        (String) response.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown");

    if (properties.isBlockOnSuspicion()) {
      // Synchronous path: block high-score responses before returning
      return analyseAndMaybeBlock(response, text, provider);
    }

    // Async path: fire-and-forget for zero latency impact
    Mono.fromRunnable(() -> analyseAsync(text, provider))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(null, ex -> log.debug("HallucinationMonitor | error | {}", ex.getMessage()));
    return response;
  }

  private ChatClientResponse analyseAndMaybeBlock(
      ChatClientResponse response, String text, String provider) {
    double score = score(text);
    if (score < properties.getThreshold()) {
      if (score > 0) log.debug("GUARDRAIL | MINOR_UNCERTAINTY | provider={} | score={}", provider, score);
      return response;
    }
    log.warn(
        "GUARDRAIL | HALLUCINATION_BLOCKED | provider={} | score={} | threshold={}",
        provider, score, properties.getThreshold());
    metricsService.recordError(provider, "HALLUCINATION_BLOCKED");

    Map<String, Object> newCtx = new HashMap<>(response.context());
    newCtx.put(CTX_BLOCKED, true);
    return AdvisorUtils.replaceResponseText(response, properties.getBlockMessage(), newCtx);
  }

  private void analyseAsync(String text, String provider) {
    double score = score(text);
    if (score >= properties.getThreshold()) {
      log.warn(
          "GUARDRAIL | HALLUCINATION_SUSPECTED | provider={} | score={} | threshold={}",
          provider, score, properties.getThreshold());
      metricsService.recordError(provider, "HALLUCINATION_SUSPECTED");
    } else if (score > 0) {
      log.debug("GUARDRAIL | MINOR_UNCERTAINTY | provider={} | score={}", provider, score);
    }
  }

  private double score(String text) {
    String lower = text.toLowerCase();
    long uncertainty = UNCERTAINTY.stream().filter(lower::contains).count();
    long signals     = SIGNALS.stream().filter(lower::contains).count();
    return uncertainty * 0.5 + signals * 1.0;
  }
}
