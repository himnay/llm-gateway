package com.llm.gateway.llm_gateway.advisor;

import com.llm.gateway.llm_gateway.security.PromptSanitizer;
import com.llm.gateway.llm_gateway.security.PromptValidationException;
import com.llm.gateway.llm_gateway.security.SanitizationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Spring AI advisor that runs PromptSanitizer before every ChatClient call.
 * Sits at HIGHEST_PRECEDENCE so injection detection is the first thing that runs,
 * both on the sync and streaming paths.
 */
@Slf4j
@Component
public class PromptGuardAdvisor implements CallAdvisor, StreamAdvisor {

    private final PromptSanitizer sanitizer;

    public PromptGuardAdvisor(PromptSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(guard(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(guard(request));
    }

    private ChatClientRequest guard(ChatClientRequest request) {
        String userText = extractUserText(request);
        if (userText == null || userText.isBlank()) {
            return request;
        }

        SanitizationResult result = sanitizer.sanitize(userText);

        if (!result.isValid()) {
            log.warn("PromptGuardAdvisor | prompt rejected | violations={}", result.getViolations());
            throw new PromptValidationException(result.getViolations());
        }

        if (result.isModified()) {
            log.info("PromptGuardAdvisor | prompt sanitized | warnings={}", result.getWarnings());
            Prompt sanitizedPrompt = request.prompt().augmentUserMessage(
                    message -> message.mutate().text(result.getSanitizedPrompt()).build());
            return request.mutate()
                    .prompt(sanitizedPrompt)
                    .build();
        }

        return request;
    }

    private String extractUserText(ChatClientRequest request) {
        var userMessage = request.prompt().getUserMessage();
        return userMessage != null ? userMessage.getText() : null;
    }
}