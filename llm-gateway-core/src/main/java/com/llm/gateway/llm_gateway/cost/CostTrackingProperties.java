package com.llm.gateway.llm_gateway.cost;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm.cost")
public class CostTrackingProperties {

    /** Master switch — set to false to skip cost estimation entirely. */
    private boolean enabled = true;

    /** When true, the estimated cost (USD) is added to every LLM response as a response header. */
    private boolean addResponseHeader = true;

    /** Name of the HTTP response header that carries the cost estimate. */
    private String headerName = "X-LLM-Cost-USD";
}
