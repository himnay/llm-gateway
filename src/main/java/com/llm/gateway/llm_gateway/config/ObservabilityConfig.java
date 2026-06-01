package com.llm.gateway.llm_gateway.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer Observation configuration.
 *
 * <p>Registers the {@link ObservedAspect} so that {@code @Observed}-annotated
 * methods are automatically wrapped in an {@link io.micrometer.observation.Observation}
 * (creating Zipkin spans + Prometheus timers without boilerplate).</p>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Enables AOP-based observation for any method or class annotated
     * with {@code @io.micrometer.observation.annotation.Observed}.
     */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    /**
     * Enables the {@code @Timed} annotation on any Spring bean method. Used to
     * record the turnaround time of each gateway operation (e.g. the blocking
     * provider call in {@code LlmGatewayFacade.execute}) as the
     * {@code llm_gateway_execution_seconds} timer — viewable per provider in Grafana.
     */
    @Bean
    TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}

