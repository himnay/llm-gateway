package com.llm.gateway.llm_gateway.advisor;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Records token-usage and latency metrics from the ChatResponse returned by Spring AI.
 * Runs at HIGHEST_PRECEDENCE + 1 so PromptGuardAdvisor always runs first.
 *
 * Advisor-params key "provider" is set by each service when building the prompt
 * so that metrics are tagged correctly per provider.
 */
@Slf4j
@Component
public class MetricsAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String PROVIDER_PARAM = "provider";

    private final LlmMetricsService metricsService;

    public MetricsAdvisor(LlmMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        String provider = resolveProvider(request);

        recordPromptLength(provider, extractUserText(request));

        try {
            ChatClientResponse response = chain.nextCall(request);
            recordSuccess(provider, response.chatResponse(), System.currentTimeMillis() - start);
            return response;
        } catch (Exception ex) {
            metricsService.recordError(provider, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        String provider = resolveProvider(request);

        recordPromptLength(provider, extractUserText(request));

        return chain.nextStream(request)
                .doOnComplete(() -> {
                    long latencyMs = System.currentTimeMillis() - start;
                    metricsService.recordRequest(provider, false);
                    metricsService.recordLatency(provider, latencyMs);
                })
                .doOnError(ex -> metricsService.recordError(provider, ex.getClass().getSimpleName()));
    }

    private String resolveProvider(ChatClientRequest request) {
        Object p = request.context().get(PROVIDER_PARAM);
        return p != null ? p.toString() : "unknown";
    }

    private String extractUserText(ChatClientRequest request) {
        var userMessage = request.prompt().getUserMessage();
        return userMessage != null ? userMessage.getText() : null;
    }

    private void recordPromptLength(String provider, String userText) {
        if (userText != null) {
            metricsService.recordPromptLength(provider, userText.length());
        }
    }

    private void recordSuccess(String provider, ChatResponse chatResponse, long latencyMs) {
        metricsService.recordRequest(provider, false);
        metricsService.recordLatency(provider, latencyMs);

        if (chatResponse != null && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            log.debug("MetricsAdvisor | provider={} | promptTokens={} | completionTokens={} | totalTokens={}",
                    provider,
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens());
        }
    }
}