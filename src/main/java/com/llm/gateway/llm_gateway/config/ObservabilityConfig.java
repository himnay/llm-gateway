package com.llm.gateway.llm_gateway.config;

import io.github.mweirauch.micrometer.jvm.extras.ProcessMemoryMetrics;
import io.github.mweirauch.micrometer.jvm.extras.ProcessThreadMetrics;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
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

    /**
     * Exposes process-level native memory metrics ({@code process_memory_vss/rss/pss/swap_bytes})
     * from micrometer-jvm-extras, feeding the Grafana JVM dashboard's "Process Memory" panel.
     *
     * <p>Backed by {@code /proc} so values are only populated on Linux (e.g. the Docker
     * runtime); on macOS/Windows the binder registers no-op gauges.</p>
     */
    @Bean
    MeterBinder processMemoryMetrics() {
        return new ProcessMemoryMetrics();
    }

    /**
     * Exposes process thread counts ({@code process_threads}) from micrometer-jvm-extras.
     * Linux-only, same as {@link #processMemoryMetrics()}.
     */
    @Bean
    MeterBinder processThreadMetrics() {
        return new ProcessThreadMetrics();
    }
}

