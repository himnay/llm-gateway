package com.llm.gateway.llm_gateway.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised guardrail pattern catalogue — the single, tunable source of truth for every regex /
 * keyword list used by the gateway's guardrails.
 *
 * <p>Bound from {@code llm.guardrails.patterns.*}. Patterns can be added, removed, or edited
 * entirely in YAML / environment configuration <b>without code changes</b>. The fields below carry
 * sensible, fail-safe defaults so protection is never accidentally disabled when the configuration
 * is absent; any value present in configuration <b>replaces</b> the corresponding default list/map.
 *
 * <p>Consumed by {@code SensitiveDataRedactor}, {@code PromptSanitizer} and {@code
 * ToxicityFilterAdvisor}.
 */
@Component
@ConfigurationProperties(prefix = "llm.guardrails.patterns")
@Data
public class GuardrailPatternProperties {

  /**
   * Sensitive-data (PII + secrets) detectors: {@code type-name -> regex}. The type name becomes the
   * redaction placeholder, e.g. {@code email -> [EMAIL]}. Insertion order matters — more specific
   * patterns should come first.
   */
  private Map<String, String> sensitiveData = defaultSensitiveData();

  /** Hard-block prompt-injection regexes (a match rejects the request with HTTP 400). */
  private List<String> injection = defaultInjection();

  /** Strip regexes — matched content is removed but the request continues. */
  private List<String> strip = defaultStrip();

  /** Lower-cased substrings that flag harmful/toxic content. */
  private List<String> toxicKeywords = defaultToxicKeywords();

  // ──────────────────────────────────────────────────────────────────────────
  // Fail-safe defaults (used unless overridden under llm.guardrails.patterns.*)
  // ──────────────────────────────────────────────────────────────────────────

  private static Map<String, String> defaultSensitiveData() {
    Map<String, String> m = new LinkedHashMap<>();
    // Secrets / credentials (most specific first)
    m.put(
        "private-key",
        "-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----");
    m.put("api-key", "\\b(?:sk|sk-ant|sk-proj|rk|pk)-[A-Za-z0-9_\\-]{16,}\\b");
    m.put("aws-key", "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");
    m.put("bearer-token", "(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{20,}");
    // PII
    m.put("email", "[\\w.%+\\-]+@[\\w.\\-]+\\.[A-Za-z]{2,}");
    m.put(
        "credit-card",
        "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b");
    m.put("ssn", "\\b\\d{3}[-\\s]\\d{2}[-\\s]\\d{4}\\b");
    m.put("iban", "\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}(?:[A-Z0-9]?){0,16}\\b");
    m.put(
        "ip-address",
        "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");
    m.put("phone", "(?:\\+?\\d{1,3}[\\s.\\-]?)?\\(?\\d{3}\\)?[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{4}\\b");
    m.put("passport", "\\b[A-Z]{1,2}\\d{6,9}\\b");
    return m;
  }

  private static List<String> defaultInjection() {
    return List.of(
        // Instruction-override attacks
        "(?i)\\bignore\\s+(?:all\\s+)?previous\\s+instructions?\\b",
        "(?i)\\bdisregard\\s+(?:all\\s+)?(?:previous\\s+)?instructions?\\b",
        "(?i)\\bforget\\s+(?:all\\s+)?(?:previous\\s+)?instructions?\\b",
        "(?i)\\boverride\\s+(?:all\\s+)?instructions?\\b",
        "(?i)\\bnew\\s+instructions?\\s*:\\s*",
        "(?i)\\byour\\s+new\\s+(?:role|task|job|instructions?)\\b",
        // Role-hijacking attacks
        "(?i)\\byou\\s+are\\s+now\\s+(?:an?\\s+)?(?:evil|harmful|dangerous|unrestricted|jailbroken)",
        "(?i)\\bact\\s+as\\s+(?:an?\\s+)?(?:evil|harmful|unrestricted|uncensored)",
        "(?i)\\bpretend\\s+(?:you\\s+are|to\\s+be)\\s+(?:an?\\s+)?(?:evil|harmful|unrestricted)",
        // Jailbreak keywords
        "(?i)\\bdan\\s+mode\\b",
        "(?i)\\bdeveloper\\s+mode\\b",
        "(?i)\\bjailbreak\\b",
        "(?i)\\bdo\\s+anything\\s+now\\b",
        // Restriction bypass
        "(?i)\\bbypass\\s+(?:all\\s+)?(?:restrictions?|filters?|safeguards?|guidelines?|content\\s+policy)\\b",
        "(?i)\\bunlock\\s+(?:your\\s+)?(?:full\\s+)?(?:capabilities?|potential|mode)\\b",
        // Delimiter injection
        "(?i)###\\s*(?:SYSTEM|INSTRUCTIONS?|PROMPT|OVERRIDE)\\s*###",
        "(?i)\\[\\[\\s*(?:SYSTEM|INSTRUCTIONS?|OVERRIDE)\\s*\\]\\]",
        "(?i)<\\s*(?:system|instructions?|override)\\s*>",
        "(?i)---\\s*SYSTEM\\s*---",
        "(?i)\\bSYSTEM:\\s",
        // Indirect exfiltration / system-prompt revelation
        "(?i)\\brepeat\\s+(?:the\\s+)?(?:system|initial)\\s+prompt\\b",
        "(?i)\\breveal\\s+(?:your\\s+)?(?:system|initial|hidden)\\s+(?:prompt|instructions?)\\b",
        "(?i)\\bprint\\s+(?:your\\s+)?(?:system|initial|hidden)\\s+(?:prompt|instructions?)\\b");
  }

  private static List<String> defaultStrip() {
    return List.of(
        "(?is)<script[^>]*>.*?</script>", // script tags (dot matches newline)
        "<[^>]{1,200}>", // generic HTML tags (bounded)
        "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", // null + control chars (keep \t \n
        // \r)
        "[\\u202A-\\u202E\\u2066-\\u2069\\u200F\\u200E]", // unicode direction overrides
        "(.)\\1{200,}" // excessive repeated chars
        );
  }

  private static List<String> defaultToxicKeywords() {
    return List.of(
        "how to make a bomb",
        "how to build a weapon",
        "step by step instructions to kill",
        "synthesize methamphetamine",
        "create malware",
        "write a virus",
        "exploit vulnerability to attack",
        "hack into systems",
        "bypass authentication systems",
        "child exploitation",
        "self-harm instructions",
        "suicide method");
  }
}
