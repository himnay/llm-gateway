package com.llm.gateway.llm_gateway.guardrail;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.Generation;

public final class AdvisorUtils {

    private AdvisorUtils() {}

    public static String extractUserText(ChatClientRequest request) {
        var msg = request.prompt().getUserMessage();
        return msg != null ? msg.getText() : null;
    }

    public static String extractResponseText(ChatClientResponse response) {
        if (response.chatResponse() == null) return null;
        Generation result = response.chatResponse().getResult();
        if (result == null || result.getOutput() == null) return null;
        return result.getOutput().getText();
    }
}
