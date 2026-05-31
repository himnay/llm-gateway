package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.observability.LlmMetricsService;
import lombok.RequiredArgsConstructor;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String PROVIDER_PARAM = "provider";

    private final LlmMetricsService metricsService;

    @Override
    public String getName() { return getClass().getSimpleName(); }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 4; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        String provider = resolveProvider(request);
        recordPromptLength(provider, AdvisorUtils.extractUserText(request));
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
        recordPromptLength(provider, AdvisorUtils.extractUserText(request));
        return chain.nextStream(request)
                .doOnComplete(() -> {
                    metricsService.recordRequest(provider, false);
                    metricsService.recordLatency(provider, System.currentTimeMillis() - start);
                })
                .doOnError(ex -> metricsService.recordError(provider, ex.getClass().getSimpleName()));
    }

    private String resolveProvider(ChatClientRequest request) {
        Object p = request.context().get(PROVIDER_PARAM);
        return p != null ? p.toString() : "unknown";
    }

    private void recordPromptLength(String provider, String text) {
        if (text != null) metricsService.recordPromptLength(provider, text.length());
    }

    private void recordSuccess(String provider, ChatResponse chatResponse, long latencyMs) {
        metricsService.recordRequest(provider, false);
        metricsService.recordLatency(provider, latencyMs);
        if (chatResponse != null && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null) {
            var u = chatResponse.getMetadata().getUsage();
            log.debug("METRICS | provider={} | prompt_tokens={} | completion_tokens={} | total_tokens={}",
                    provider, u.getPromptTokens(), u.getCompletionTokens(), u.getTotalTokens());
        }
    }
}
