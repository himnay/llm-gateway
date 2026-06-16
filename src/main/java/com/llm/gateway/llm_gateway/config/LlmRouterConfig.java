package com.llm.gateway.llm_gateway.config;

import com.llm.gateway.llm_gateway.admin.AdminHandler;
import com.llm.gateway.llm_gateway.embed.EmbeddingHandler;
import com.llm.gateway.llm_gateway.handler.LlmHandler;
import com.llm.gateway.llm_gateway.handler.LlmStreamHandler;
import com.llm.gateway.llm_gateway.image.ImageHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Functional router. Base path /llm is set via spring.webflux.base-path in application.yaml.
 */
@Configuration
public class LlmRouterConfig {

    @Bean
    public RouterFunction<ServerResponse> llmRoutes(LlmHandler handler,
                                                    LlmStreamHandler streamHandler,
                                                    ImageHandler imageHandler,
                                                    EmbeddingHandler embeddingHandler,
                                                    AdminHandler adminHandler) {
        return RouterFunctions.route()
                // ── Public info ───────────────────────────────────────────────
                .GET( "/health",                     handler::health)
                .GET( "/providers",                  handler::providers)
                .GET( "/models",                     handler::models)
                // ── LLM inference ─────────────────────────────────────────────
                .POST("/query",                      handler::query)
                .POST("/failover",                   handler::failoverQuery)
                .POST("/chat",                       handler::chat)
                .POST("/image",                      imageHandler::generate)
                .POST("/embed",                      embeddingHandler::embed)
                .POST("/openai/extract",             handler::extractStructured)
                .POST("/{provider}/chat",            handler::perProviderChat)
                .POST("/{provider}/stream",          streamHandler::stream)
                // ── Session management ────────────────────────────────────────
                .DELETE("/sessions/{sessionId}",     handler::deleteSession)
                // ── Admin — API key management (requires auth) ────────────────
                .GET(  "/admin/keys",                adminHandler::listKeys)
                .POST( "/admin/keys",                adminHandler::createKey)
                .PATCH("/admin/keys/{id}",           adminHandler::updateKey)
                .DELETE("/admin/keys/{id}",          adminHandler::deleteKey)
                .build();
    }
}
