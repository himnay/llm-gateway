package com.llm.gateway.llm_gateway.security;

import com.llm.gateway.llm_gateway.config.GuardrailPatternProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prompt Sanitizer – protects the gateway against prompt injection attacks.
 *
 * <h3>Strategy</h3>
 *
 * <ul>
 *   <li><b>Hard-block patterns</b> – the request is rejected with HTTP 400 when any of these are
 *       found (configurable via {@code llm.security.sanitization.block-on-violation}).
 *   <li><b>Strip patterns</b> – dangerous markup / control characters are silently removed; the
 *       sanitized prompt is sent to the LLM and the response carries {@code sanitized=true}.
 *   <li><b>Length limit</b> – prompts exceeding {@code llm.security.sanitization.max-prompt-length}
 *       characters are rejected.
 * </ul>
 *
 * <p>The injection and strip pattern lists are externalised in {@link GuardrailPatternProperties}
 * ({@code llm.guardrails.patterns.injection} / {@code llm.guardrails.patterns.strip}) so they can
 * be tuned without code changes.
 */
@Slf4j
@Component
public class PromptSanitizer {

  @Value("${llm.security.sanitization.enabled:true}")
  private boolean enabled;

  @Value("${llm.security.sanitization.max-prompt-length:10000}")
  private int maxPromptLength;

  @Value("${llm.security.sanitization.block-injection-patterns:true}")
  private boolean blockInjectionPatterns;

  @Value("${llm.security.sanitization.block-on-violation:true}")
  private boolean blockOnViolation;

  // ──────────────────────────────────────────────────────────────────────────
  // Patterns are externalised in GuardrailPatternProperties (llm.guardrails.patterns.*)
  // so they can be tuned without code changes. Compiled once at construction.
  // ──────────────────────────────────────────────────────────────────────────
  private final List<Pattern> injectionBlockPatterns;
  private final List<Pattern> stripPatterns;

  public PromptSanitizer(GuardrailPatternProperties patterns) {
    this.injectionBlockPatterns = compile(patterns.getInjection(), "injection");
    this.stripPatterns = compile(patterns.getStrip(), "strip");
    log.info(
        "SECURITY | PromptSanitizer initialised | injectionPatterns={} | stripPatterns={}",
        injectionBlockPatterns.size(),
        stripPatterns.size());
  }

  private static List<Pattern> compile(List<String> regexes, String label) {
    List<Pattern> compiled = new ArrayList<>();
    for (String regex : regexes) {
      try {
        compiled.add(Pattern.compile(regex));
      } catch (PatternSyntaxException ex) {
        log.error(
            "SECURITY | invalid {} regex skipped | regex='{}' | error={}",
            label,
            regex,
            ex.getMessage());
      }
    }
    return compiled;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Public API
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Validates and sanitizes the given prompt.
   *
   * @param prompt raw user prompt
   * @return {@link SanitizationResult} with {@code valid=false} if the request should be rejected,
   *     or the (possibly modified) safe prompt otherwise.
   */
  public SanitizationResult sanitize(String prompt) {
    if (!enabled) {
      return SanitizationResult.builder()
          .valid(true)
          .modified(false)
          .sanitizedPrompt(prompt)
          .violations(List.of())
          .warnings(List.of())
          .build();
    }

    List<String> violations = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    // ── 1. Null / blank ──────────────────────────────────────────────────
    if (prompt == null || prompt.isBlank()) {
      violations.add("Prompt must not be empty.");
      return failResult(prompt, violations, warnings);
    }

    // ── 2. Length limit ──────────────────────────────────────────────────
    if (prompt.length() > maxPromptLength) {
      violations.add(
          String.format(
              "Prompt length %d exceeds the allowed maximum of %d characters.",
              prompt.length(), maxPromptLength));
      return failResult(prompt, violations, warnings);
    }

    // ── 3. Hard-block injection patterns ────────────────────────────────
    if (blockInjectionPatterns) {
      for (Pattern p : injectionBlockPatterns) {
        Matcher m = p.matcher(prompt);
        if (m.find()) {
          String excerpt = m.group().length() > 80 ? m.group().substring(0, 80) + "…" : m.group();
          violations.add("Potential prompt injection detected: \"" + excerpt + "\"");
          log.warn(
              "SECURITY | Prompt injection pattern matched | pattern='{}' | excerpt='{}'",
              p.pattern(),
              excerpt);
        }
      }
      if (!violations.isEmpty() && blockOnViolation) {
        return failResult(prompt, violations, warnings);
      }
    }

    // ── 4. Strip dangerous content ───────────────────────────────────────
    String sanitized = prompt;
    for (Pattern p : stripPatterns) {
      String after = p.matcher(sanitized).replaceAll(" ");
      if (!after.equals(sanitized)) {
        warnings.add("Unsafe content was stripped from the prompt.");
        log.debug("SECURITY | Sanitized content | pattern='{}'", p.pattern());
        sanitized = after;
      }
    }

    // ── 5. Normalize whitespace / line endings ───────────────────────────
    sanitized =
        sanitized
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replaceAll("[ \t]{3,}", "  ") // collapse excessive spaces/tabs
            .trim();

    boolean modified = !sanitized.equals(prompt);
    if (modified) {
      warnings.add("Prompt was normalized (whitespace / encoding).");
    }

    return SanitizationResult.builder()
        .valid(violations.isEmpty())
        .modified(modified)
        .sanitizedPrompt(sanitized)
        .violations(violations)
        .warnings(warnings)
        .build();
  }

  // ──────────────────────────────────────────────────────────────────────────
  private SanitizationResult failResult(
      String original, List<String> violations, List<String> warnings) {
    return SanitizationResult.builder()
        .valid(false)
        .modified(false)
        .sanitizedPrompt(original)
        .violations(violations)
        .warnings(warnings)
        .build();
  }
}
