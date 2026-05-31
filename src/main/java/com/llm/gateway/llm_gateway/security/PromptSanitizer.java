package com.llm.gateway.llm_gateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt Sanitizer – protects the gateway against prompt injection attacks.
 *
 * <h3>Strategy</h3>
 * <ul>
 *   <li><b>Hard-block patterns</b> – the request is rejected with HTTP 400 when
 *       any of these are found (configurable via {@code llm.security.sanitization.block-on-violation}).</li>
 *   <li><b>Strip patterns</b> – dangerous markup / control characters are silently
 *       removed; the sanitized prompt is sent to the LLM and the response carries
 *       {@code sanitized=true}.</li>
 *   <li><b>Length limit</b> – prompts exceeding {@code llm.security.sanitization.max-prompt-length}
 *       characters are rejected.</li>
 * </ul>
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
    // Hard-block patterns – indicate a deliberate prompt injection attempt
    // ──────────────────────────────────────────────────────────────────────────
    private static final List<Pattern> INJECTION_BLOCK_PATTERNS = List.of(
        // Instruction-override attacks
        Pattern.compile("(?i)\\bignore\\s+(?:all\\s+)?previous\\s+instructions?\\b"),
        Pattern.compile("(?i)\\bdisregard\\s+(?:all\\s+)?(?:previous\\s+)?instructions?\\b"),
        Pattern.compile("(?i)\\bforget\\s+(?:all\\s+)?(?:previous\\s+)?instructions?\\b"),
        Pattern.compile("(?i)\\boverride\\s+(?:all\\s+)?instructions?\\b"),
        Pattern.compile("(?i)\\bnew\\s+instructions?\\s*:\\s*"),
        Pattern.compile("(?i)\\byour\\s+new\\s+(?:role|task|job|instructions?)\\b"),

        // Role-hijacking attacks
        Pattern.compile("(?i)\\byou\\s+are\\s+now\\s+(?:an?\\s+)?(?:evil|harmful|dangerous|unrestricted|jailbroken)"),
        Pattern.compile("(?i)\\bact\\s+as\\s+(?:an?\\s+)?(?:evil|harmful|unrestricted|uncensored)"),
        Pattern.compile("(?i)\\bpretend\\s+(?:you\\s+are|to\\s+be)\\s+(?:an?\\s+)?(?:evil|harmful|unrestricted)"),

        // Jailbreak keywords
        Pattern.compile("(?i)\\bdan\\s+mode\\b"),
        Pattern.compile("(?i)\\bdeveloper\\s+mode\\b"),
        Pattern.compile("(?i)\\bjailbreak\\b"),
        Pattern.compile("(?i)\\bdo\\s+anything\\s+now\\b"),

        // Restriction bypass
        Pattern.compile("(?i)\\bbypass\\s+(?:all\\s+)?(?:restrictions?|filters?|safeguards?|guidelines?|content\\s+policy)\\b"),
        Pattern.compile("(?i)\\bunlock\\s+(?:your\\s+)?(?:full\\s+)?(?:capabilities?|potential|mode)\\b"),

        // Delimiter injection (attempting to inject a fake system-prompt boundary)
        Pattern.compile("(?i)###\\s*(?:SYSTEM|INSTRUCTIONS?|PROMPT|OVERRIDE)\\s*###"),
        Pattern.compile("(?i)\\[\\[\\s*(?:SYSTEM|INSTRUCTIONS?|OVERRIDE)\\s*\\]\\]"),
        Pattern.compile("(?i)<\\s*(?:system|instructions?|override)\\s*>"),
        Pattern.compile("(?i)---\\s*SYSTEM\\s*---"),
        Pattern.compile("(?i)\\bSYSTEM:\\s"),

        // Indirect exfiltration / system-prompt revelation
        Pattern.compile("(?i)\\brepeat\\s+(?:the\\s+)?(?:system|initial)\\s+prompt\\b"),
        Pattern.compile("(?i)\\breveal\\s+(?:your\\s+)?(?:system|initial|hidden)\\s+(?:prompt|instructions?)\\b"),
        Pattern.compile("(?i)\\bprint\\s+(?:your\\s+)?(?:system|initial|hidden)\\s+(?:prompt|instructions?)\\b")
    );

    // ──────────────────────────────────────────────────────────────────────────
    // Strip patterns – remove dangerous content but keep the request alive
    // ──────────────────────────────────────────────────────────────────────────
    private static final List<Pattern> STRIP_PATTERNS = List.of(
        // Script / HTML injection
        Pattern.compile("(?i)<script[^>]*>.*?</script>", Pattern.DOTALL),
        Pattern.compile("<[^>]{1,200}>"),   // generic HTML tags (bounded)

        // Null bytes and most ASCII control characters (keep \t, \n, \r)
        Pattern.compile("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"),

        // Unicode direction overrides (bidirectional text attacks)
        Pattern.compile("[\u202A-\u202E\u2066-\u2069\u200F\u200E]"),

        // Excessive repeated characters (often used to confuse tokenizers)
        Pattern.compile("(.)\\1{200,}")
    );

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Validates and sanitizes the given prompt.
     *
     * @param prompt raw user prompt
     * @return {@link SanitizationResult} with {@code valid=false} if the request
     *         should be rejected, or the (possibly modified) safe prompt otherwise.
     */
    public SanitizationResult sanitize(String prompt) {
        if (!enabled) {
            return SanitizationResult.builder()
                    .valid(true).modified(false)
                    .sanitizedPrompt(prompt)
                    .violations(List.of()).warnings(List.of())
                    .build();
        }

        List<String> violations = new ArrayList<>();
        List<String> warnings   = new ArrayList<>();

        // ── 1. Null / blank ──────────────────────────────────────────────────
        if (prompt == null || prompt.isBlank()) {
            violations.add("Prompt must not be empty.");
            return failResult(prompt, violations, warnings);
        }

        // ── 2. Length limit ──────────────────────────────────────────────────
        if (prompt.length() > maxPromptLength) {
            violations.add(String.format(
                "Prompt length %d exceeds the allowed maximum of %d characters.",
                prompt.length(), maxPromptLength));
            return failResult(prompt, violations, warnings);
        }

        // ── 3. Hard-block injection patterns ────────────────────────────────
        if (blockInjectionPatterns) {
            for (Pattern p : INJECTION_BLOCK_PATTERNS) {
                Matcher m = p.matcher(prompt);
                if (m.find()) {
                    String excerpt = m.group().length() > 80 ? m.group().substring(0, 80) + "…" : m.group();
                    violations.add("Potential prompt injection detected: \"" + excerpt + "\"");
                    log.warn("SECURITY | Prompt injection pattern matched | pattern='{}' | excerpt='{}'",
                            p.pattern(), excerpt);
                }
            }
            if (!violations.isEmpty() && blockOnViolation) {
                return failResult(prompt, violations, warnings);
            }
        }

        // ── 4. Strip dangerous content ───────────────────────────────────────
        String sanitized = prompt;
        for (Pattern p : STRIP_PATTERNS) {
            String after = p.matcher(sanitized).replaceAll(" ");
            if (!after.equals(sanitized)) {
                warnings.add("Unsafe content was stripped from the prompt.");
                log.debug("SECURITY | Sanitized content | pattern='{}'", p.pattern());
                sanitized = after;
            }
        }

        // ── 5. Normalize whitespace / line endings ───────────────────────────
        sanitized = sanitized
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("[ \t]{3,}", "  ")   // collapse excessive spaces/tabs
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
    private SanitizationResult failResult(String original,
                                          List<String> violations,
                                          List<String> warnings) {
        return SanitizationResult.builder()
                .valid(false)
                .modified(false)
                .sanitizedPrompt(original)
                .violations(violations)
                .warnings(warnings)
                .build();
    }
}

