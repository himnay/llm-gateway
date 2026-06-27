package com.llm.gateway.llm_gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.features")
public class FeatureFlagProperties {
    private boolean streamingEnabled = true;
    private boolean semanticCacheEnabled = true;
    private boolean autoFailoverEnabled = true;
    private boolean externalGuardrailsEnabled = true;
    private boolean hallucinationGuardEnabled = true;
    private boolean costTrackingEnabled = true;
    private boolean auditLoggingEnabled = true;
}
