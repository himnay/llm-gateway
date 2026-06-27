package com.llm.gateway.llm_gateway;

import com.llm.gateway.llm_gateway.config.FeatureFlagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import reactor.core.publisher.Hooks;

@SpringBootApplication
@EnableConfigurationProperties(FeatureFlagProperties.class)
public class LLMGatewayApplication {

  public static void main(String[] args) {
    // Propagates ThreadLocal (MDC) values across Reactor scheduler hops automatically.
    // Requires Reactor 3.6+ (included via Spring Boot 3.2+).
    Hooks.enableAutomaticContextPropagation();
    SpringApplication.run(LLMGatewayApplication.class, args);
  }
}
