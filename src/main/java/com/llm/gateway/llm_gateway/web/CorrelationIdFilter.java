package com.llm.gateway.llm_gateway.web;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

/**
 * WebFlux filter that propagates a per-request correlation ID through the
 * entire request/response lifecycle.
 *
 * <ul>
 *   <li>Reads {@code X-Request-ID} from the inbound request header.</li>
 *   <li>Generates a random UUID if the header is absent or blank.</li>
 *   <li>Stores the ID in {@link ServerWebExchange} attributes under key
 *       {@code "requestId"} so that handlers can retrieve it without
 *       touching the reactive context.</li>
 *   <li>Publishes the ID into the Reactor {@link Context} under key
 *       {@code "requestId"} so that downstream reactive chains can
 *       read it via {@code Mono.deferContextual} / {@code contextWrite}.</li>
 *   <li>Returns the ID in the {@code X-Request-ID} response header so that
 *       clients can correlate their requests with gateway logs.</li>
 *   <li>Sets the ID in SLF4J {@code MDC} for the duration of the filter
 *       chain using a {@code doFinally} hook to clean up.</li>
 * </ul>
 *
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the ID is available
 * before any other filter (including the Redis rate-limiter) executes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER      = "X-Request-ID";
    public static final String ATTR_KEY    = "requestId";
    public static final String CONTEXT_KEY = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        final String id = requestId;

        // Make available to non-reactive code (handlers, facades) via exchange attributes.
        exchange.getAttributes().put(ATTR_KEY, id);

        // Set in MDC for synchronous log calls executed on the same thread.
        MDC.put(ATTR_KEY, id);

        return chain.filter(exchange)
                // Propagate through the reactive Subscriber context for reactive chains.
                .contextWrite(Context.of(CONTEXT_KEY, id))
                // Ensure the response header is set before the first byte is written.
                .doFirst(() -> exchange.getResponse().getHeaders().set(HEADER, id))
                // Always clean up MDC, even on error or cancellation.
                .doFinally(signal -> MDC.remove(ATTR_KEY));
    }
}
