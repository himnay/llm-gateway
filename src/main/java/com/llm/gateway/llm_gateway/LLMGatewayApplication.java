package com.llm.gateway.llm_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class LLMGatewayApplication {

	public static void main(String[] args) {
		// Propagates ThreadLocal (MDC) values across Reactor scheduler hops automatically.
		// Requires Reactor 3.6+ (included via Spring Boot 3.2+).
		Hooks.enableAutomaticContextPropagation();
		SpringApplication.run(LLMGatewayApplication.class, args);
	}

}
