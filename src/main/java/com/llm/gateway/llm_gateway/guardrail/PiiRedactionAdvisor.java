package com.llm.gateway.llm_gateway.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * INPUT + OUTPUT guardrail — PII detection and redaction.
 *
 * INPUT  : replaces detected PII with typed placeholders before the LLM sees it.
 * OUTPUT : async scan to flag any PII leakage in the response (non-blocking).
 *
 * NOTE: Regex-based PII detection has inherent limitations (false positives and
 * negatives). For production workloads handling sensitive data, replace or augment
 * with a dedicated service (e.g. AWS Comprehend, Azure AI Content Safety, or
 * Microsoft Presidio).
 */
@Slf4j
@Component
public class PiiRedactionAdvisor implements BaseAdvisor {

    private static final Map<String, Pattern> PII_PATTERNS = new LinkedHashMap<>();

    static {
        PII_PATTERNS.put("[EMAIL]",       Pattern.compile("[\\w.%+\\-]+@[\\w.\\-]+\\.[A-Za-z]{2,}", Pattern.CASE_INSENSITIVE));
        PII_PATTERNS.put("[PHONE]",       Pattern.compile("\\+?[0-9][\\s\\-.()]?\\(?[0-9]{3}\\)?[\\s\\-.]?[0-9]{3}[\\s\\-.]?[0-9]{4,6}"));
        PII_PATTERNS.put("[CREDIT_CARD]", Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b"));
        PII_PATTERNS.put("[SSN]",         Pattern.compile("\\b\\d{3}[-\\s]\\d{2}[-\\s]\\d{4}\\b"));
        PII_PATTERNS.put("[IP_ADDRESS]",  Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b"));
        PII_PATTERNS.put("[IBAN]",        Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}(?:[A-Z0-9]?){0,16}\\b"));
        PII_PATTERNS.put("[PASSPORT]",    Pattern.compile("\\b[A-Z]{1,2}\\d{6,9}\\b"));
    }

    @Value("${llm.guardrails.pii-redaction.enabled:true}")
    private boolean enabled;

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 2; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) return request;

        String text = AdvisorUtils.extractUserText(request);
        if (text == null || text.isBlank()) return request;

        RedactionResult result = redact(text);
        if (!result.redacted()) return request;

        log.info("GUARDRAIL | PII_REDACTED | types={}", result.types());
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(
                        m -> m.mutate().text(result.sanitized()).build()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) return response;

        String text = AdvisorUtils.extractResponseText(response);
        if (text == null || text.isBlank()) return response;

        String provider = (String) response.context().getOrDefault(MetricsAdvisor.PROVIDER_PARAM, "unknown");
        Mono.fromRunnable(() -> scanOutput(text, provider))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, ex -> log.debug("PiiRedactionAdvisor | scan error | {}", ex.getMessage()));

        return response;
    }

    private void scanOutput(String text, String provider) {
        for (Map.Entry<String, Pattern> entry : PII_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                log.warn("GUARDRAIL | PII_LEAK_IN_RESPONSE | provider={} | type={}", provider, entry.getKey());
            }
        }
    }

    private RedactionResult redact(String text) {
        String result = text;
        StringBuilder types = new StringBuilder();
        for (Map.Entry<String, Pattern> entry : PII_PATTERNS.entrySet()) {
            String replaced = entry.getValue().matcher(result).replaceAll(entry.getKey());
            if (!replaced.equals(result)) {
                types.append(entry.getKey()).append(" ");
                result = replaced;
            }
        }
        return new RedactionResult(!result.equals(text), result, types.toString().trim());
    }

    record RedactionResult(boolean redacted, String sanitized, String types) {}
}
