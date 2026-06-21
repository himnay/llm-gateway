package com.llm.gateway.llm_gateway.security;

import com.llm.gateway.llm_gateway.config.GuardrailPatternProperties;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Central, provider-agnostic sensitive-data redactor — the single source of truth for PII and
 * secret detection across the gateway.
 *
 * <p>It is applied by {@link com.llm.gateway.llm_gateway.facade.LlmGatewayFacade} on the request
 * path (before <b>any</b> provider sees the prompt) and on the response path (before the caller
 * receives the content), so coverage is uniform across all providers — including the custom REST
 * providers (Google, Cohere, HuggingFace) that do not run through the Spring AI advisor chain. The
 * {@link com.llm.gateway.llm_gateway.guardrail.PiiRedactionAdvisor} also delegates here for the
 * ChatClient providers (defence-in-depth).
 *
 * <p>Patterns are externalised in {@link GuardrailPatternProperties} ({@code
 * llm.guardrails.patterns.sensitive-data}) — add or remove detectors purely in configuration. Each
 * {@code type-name} becomes the placeholder, e.g. {@code email -> [EMAIL]}.
 *
 * <p><b>NOTE:</b> regex detection has inherent false-positive/negative limits. For regulated
 * workloads, augment with a dedicated service (AWS Comprehend, Azure AI Content Safety, Microsoft
 * Presidio).
 */
@Slf4j
@Component
public class SensitiveDataRedactor {

  /** Ordered {@code placeholder -> compiled regex}; insertion order is preserved. */
  private final Map<String, Pattern> patterns = new LinkedHashMap<>();

  public SensitiveDataRedactor(GuardrailPatternProperties properties) {
    properties
        .getSensitiveData()
        .forEach(
            (type, regex) -> {
              try {
                patterns.put(toPlaceholder(type), Pattern.compile(regex));
              } catch (PatternSyntaxException ex) {
                log.error(
                    "GUARDRAIL | invalid sensitive-data regex skipped | type={} | error={}",
                    type,
                    ex.getMessage());
              }
            });
    log.info(
        "GUARDRAIL | sensitive-data redactor initialised with {} pattern(s): {}",
        patterns.size(),
        patterns.keySet());
  }

  /** {@code "api-key"} → {@code "[API_KEY]"}. */
  private static String toPlaceholder(String type) {
    return "[" + type.trim().toUpperCase().replace('-', '_').replace('.', '_') + "]";
  }

  /**
   * Replaces every detected PII/secret span with its typed placeholder.
   *
   * @param text raw text (prompt or response); {@code null}/blank is returned unchanged
   * @return the redaction outcome — never {@code null}
   */
  public Result redact(String text) {
    if (text == null || text.isBlank()) {
      return new Result(false, text, Set.of());
    }
    String result = text;
    Set<String> types = new LinkedHashSet<>();
    for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
      String replaced = e.getValue().matcher(result).replaceAll(e.getKey());
      if (!replaced.equals(result)) {
        types.add(e.getKey());
        result = replaced;
      }
    }
    return new Result(!result.equals(text), result, types);
  }

  /**
   * Detects (without modifying) which sensitive-data categories appear in the text. Used for
   * non-blocking leak monitoring of LLM responses.
   */
  public Set<String> detect(String text) {
    if (text == null || text.isBlank()) return Set.of();
    Set<String> types = new LinkedHashSet<>();
    for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
      if (e.getValue().matcher(text).find()) {
        types.add(e.getKey());
      }
    }
    return types;
  }

  /**
   * @param redacted whether any replacement was made
   * @param text the (possibly) redacted text
   * @param types the set of placeholder types that were applied
   */
  public record Result(boolean redacted, String text, Set<String> types) {}
}
