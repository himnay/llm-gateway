package com.llm.gateway.llm_gateway.config;

import com.llm.gateway.llm_gateway.web.CorrelationIdFilter;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    /**
     * ExchangeFilterFunction that reads the correlation / request ID from the
     * current SLF4J MDC (populated by {@link CorrelationIdFilter}) and forwards
     * it as an {@code X-Correlation-ID} header on every outbound WebClient call.
     *
     * <p>Falls back to the Reactor context if MDC is empty (e.g. when the call is
     * made from a non-MDC-enriched scheduler thread). If neither source contains
     * the ID, no header is added so the call proceeds without it.
     */
    private static ExchangeFilterFunction correlationIdFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request ->
                Mono.deferContextual(ctx -> {
                    // Prefer MDC (set by CorrelationIdFilter for synchronous access);
                    // fall back to the Reactor context for async callers.
                    String requestId = MDC.get(CorrelationIdFilter.ATTR_KEY);
                    if (requestId == null || requestId.isBlank()) {
                        requestId = ctx.getOrDefault(CorrelationIdFilter.CONTEXT_KEY, null);
                    }
                    if (requestId != null && !requestId.isBlank()) {
                        final String id = requestId;
                        return Mono.just(ClientRequest.from(request)
                                .header("X-Correlation-ID", id)
                                .build());
                    }
                    return Mono.just(request);
                })
        );
    }

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .responseTimeout(java.time.Duration.ofSeconds(60))
                .doOnConnected(conn ->
                    conn.addHandlerLast(new ReadTimeoutHandler(60, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(correlationIdFilter())
                .build();
    }
}

