package com.llm.gateway.llm_gateway.template;

import com.llm.gateway.llm_gateway.dto.LlmRequest;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Loads per-provider .st prompt templates and renders them with caller-supplied variables. Falls
 * back to raw systemPrompt when no templateVars are provided (backward compat).
 */
@Service
public class PromptTemplateService {

  @Value("classpath:prompts/system-default.st")
  private Resource defaultSystemTemplate;

  @Value("classpath:prompts/system-openai.st")
  private Resource openAiSystemTemplate;

  @Value("classpath:prompts/system-anthropic.st")
  private Resource anthropicSystemTemplate;

  @Value("classpath:prompts/system-ollama.st")
  private Resource ollamaSystemTemplate;

  @Value("classpath:prompts/assistant-starter.st")
  private Resource assistantStarterTemplate;

  private Map<String, PromptTemplate> templateCache;
  private PromptTemplate assistantStarterCached;

  @PostConstruct
  void initTemplateCache() {
    templateCache = Map.of(
        "openai",    new PromptTemplate(openAiSystemTemplate),
        "anthropic", new PromptTemplate(anthropicSystemTemplate),
        "ollama",    new PromptTemplate(ollamaSystemTemplate),
        "default",   new PromptTemplate(defaultSystemTemplate)
    );
    assistantStarterCached = new PromptTemplate(assistantStarterTemplate);
  }

  /**
   * Returns the resolved system prompt for the given provider and request. Priority: 1. If
   * templateVars present → render per-provider .st template with merged vars. 2. If systemPrompt
   * set (no templateVars) → return it unchanged (backward compat). 3. Otherwise → render
   * per-provider .st template with defaults only.
   */
  public String renderSystemPrompt(String provider, LlmRequest request) {
    Map<String, Object> vars = request.getTemplateVars();
    boolean hasVars = vars != null && !vars.isEmpty();

    if (!hasVars && request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
      return request.getSystemPrompt();
    }

    Map<String, Object> merged = defaultVars();
    if (hasVars) {
      merged.putAll(vars);
    }
    return resolveSystemTemplate(provider).render(merged).stripTrailing();
  }

  /**
   * Renders the assistant-starter.st template with the supplied vars. Returns null if the request
   * carries no assistantMessage and no vars with a "starter" key.
   */
  public String renderAssistantMessage(LlmRequest request) {
    if (request.getAssistantMessage() != null && !request.getAssistantMessage().isBlank()) {
      return request.getAssistantMessage();
    }
    Map<String, Object> vars = request.getTemplateVars();
    if (vars != null && vars.containsKey("starter")) {
      Map<String, Object> merged = defaultVars();
      merged.putAll(vars);
      return assistantStarterCached.render(merged).stripTrailing();
    }
    return null;
  }

  private PromptTemplate resolveSystemTemplate(String provider) {
    return templateCache.getOrDefault(provider.toLowerCase(), templateCache.get("default"));
  }

  private Map<String, Object> defaultVars() {
    Map<String, Object> defaults = new HashMap<>();
    defaults.put("role", "a helpful AI assistant");
    defaults.put("context", "");
    defaults.put("language", "English");
    defaults.put("date", LocalDate.now().toString());
    defaults.put("starter", "I understand your request. Let me help you with that.");
    return defaults;
  }
}
