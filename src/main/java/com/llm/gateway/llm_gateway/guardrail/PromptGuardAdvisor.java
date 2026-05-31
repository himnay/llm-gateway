package com.llm.gateway.llm_gateway.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * INPUT checkpoint — logs prompt length at the advisor-chain entry point.
 * Prompt injection sanitization is already handled upstream by LlmGatewayFacade
 * (before the cache lookup and observation span), so this advisor is a pure
 * pass-through that adds no duplicate scanning overhead.
 */
@Slf4j
@Component
public class PromptGuardAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() { return getClass().getSimpleName(); }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logPrompt(request);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logPrompt(request);
        return chain.nextStream(request);
    }

    private void logPrompt(ChatClientRequest request) {
        String text = AdvisorUtils.extractUserText(request);
        if (text != null) {
            log.debug("GUARDRAIL | PromptGuard | prompt_length={}", text.length());
        }
    }
}
