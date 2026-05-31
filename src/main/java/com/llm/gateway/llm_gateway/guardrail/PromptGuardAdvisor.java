package com.llm.gateway.llm_gateway.guardrail;

import com.llm.gateway.llm_gateway.security.PromptSanitizer;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.security.SanitizationResult;
import lombok.RequiredArgsConstructor;
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
 * INPUT guardrail — runs PromptSanitizer at HIGHEST_PRECEDENCE.
 * Blocks prompt injection attacks and strips dangerous markup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptGuardAdvisor implements CallAdvisor, StreamAdvisor {

    private final PromptSanitizer sanitizer;

    @Override
    public String getName() { return getClass().getSimpleName(); }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(guard(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(guard(request));
    }

    private ChatClientRequest guard(ChatClientRequest request) {
        String text = extractUserText(request);
        if (text == null || text.isBlank()) return request;

        SanitizationResult result = sanitizer.sanitize(text);

        if (!result.isValid()) {
            log.warn("GUARDRAIL | PROMPT_INJECT | violations={}", result.getViolations());
            throw new PromptValidationException(result.getViolations());
        }

        if (result.isModified()) {
            log.info("GUARDRAIL | PROMPT_SANITIZED | warnings={}", result.getWarnings());
            return request.mutate()
                    .prompt(request.prompt().augmentUserMessage(
                            m -> m.mutate().text(result.getSanitizedPrompt()).build()))
                    .build();
        }

        return request;
    }

    private String extractUserText(ChatClientRequest request) {
        var msg = request.prompt().getUserMessage();
        return msg != null ? msg.getText() : null;
    }
}
