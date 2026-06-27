package com.llm.gateway.llm_gateway.guardrail;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
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

  /**
   * Returns a new {@link ChatClientResponse} with the chat response text replaced by
   * {@code newText} and the context replaced by {@code newContext}. Used by blocking
   * advisors (e.g. {@link HallucinationMonitorAdvisor}) to substitute a safe message
   * without throwing an exception.
   */
  public static ChatClientResponse replaceResponseText(
      ChatClientResponse original, String newText, Map<String, Object> newContext) {
    Generation replacement = new Generation(new AssistantMessage(newText));
    ChatResponse newChatResponse = new ChatResponse(List.of(replacement));
    return new ChatClientResponse(newChatResponse, newContext);
  }
}
