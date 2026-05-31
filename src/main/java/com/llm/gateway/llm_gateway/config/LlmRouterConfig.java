package com.llm.gateway.llm_gateway.config;

import com.llm.gateway.llm_gateway.handler.LlmHandler;
import com.llm.gateway.llm_gateway.handler.LlmStreamHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Functional router. Base path /llm is set via spring.webflux.base-path in application.yaml.
 *
 * Route map (all relative to /llm):
 *   GET  /health | /providers | /models
 *   POST /query                  – unified, provider from request body
 *   POST /failover               – cascades through provider chain on failure
 *   POST /chat                   – multi-turn (session_id required)
 *   POST /openai/extract         – structured JSON extraction
 *   POST /{provider}/chat        – per-provider blocking call
 *   POST /{provider}/stream      – per-provider SSE streaming
 *
 * Note: specific paths (/chat, /failover, /query) are registered BEFORE
 * /{provider}/... pattern routes to prevent shadowing.
 */
@Configuration
public class LlmRouterConfig {

    @Bean
    public RouterFunction<ServerResponse> llmRoutes(LlmHandler handler, LlmStreamHandler streamHandler) {
        return RouterFunctions.route()
                .GET("/health",             handler::health)
                .GET("/providers",          handler::providers)
                .GET("/models",             handler::models)
                .POST("/query",             handler::query)
                .POST("/failover",          handler::failoverQuery)
                .POST("/chat",              handler::chat)
                .POST("/openai/extract",    handler::extractStructured)
                .POST("/{provider}/chat",   handler::perProviderChat)
                .POST("/{provider}/stream", streamHandler::stream)
                .build();
    }
}
