package com.llm.gateway.llm_gateway.config;

import com.llm.gateway.llm_gateway.dto.ImageGenerationRequest;
import com.llm.gateway.llm_gateway.dto.ImageGenerationResponse;
import com.llm.gateway.llm_gateway.dto.LlmRequest;
import com.llm.gateway.llm_gateway.dto.LlmResponse;
import com.llm.gateway.llm_gateway.embed.EmbeddingHandler;
import com.llm.gateway.llm_gateway.handler.LlmHandler;
import com.llm.gateway.llm_gateway.handler.LlmStreamHandler;
import com.llm.gateway.llm_gateway.image.ImageHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springdoc.core.annotations.RouterOperation;
import org.springdoc.core.annotations.RouterOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Functional router. Base path /llm/v1 is set via spring.webflux.base-path in application.yaml.
 *
 * <p>Routes are documented for springdoc/Swagger via {@code @RouterOperation} below, since
 * functional endpoints (RouterFunction) aren't auto-discovered the way @RestController methods are.
 */
@Configuration
public class LlmRouterConfig {

    @RouterOperation(
  @RouterOperations({
        path = "/health",
        method = RequestMethod.GET,
        beanClass = LlmHandler.class,
        beanMethod = "health",
        operation =
            @Operation(
                operationId = "health",
                tags = "Info",
                summary = "Gateway health check",
                description =
                    "Checks gateway liveness and Redis connectivity. Public, no API key required.")),
    @RouterOperation(
        path = "/providers",
        method = RequestMethod.GET,
        beanClass = LlmHandler.class,
        beanMethod = "providers",
        operation =
            @Operation(
                operationId = "providers",
                tags = "Info",
                summary = "List registered providers",
                description =
                    "Returns the set of LLM providers currently enabled and registered in the gateway.")),
    @RouterOperation(
        path = "/models",
        method = RequestMethod.GET,
        beanClass = LlmHandler.class,
        beanMethod = "models",
        operation =
            @Operation(
                operationId = "models",
                tags = "Info",
                summary = "List available models per provider",
                description =
                    "Returns the configured default model plus well-known alternatives for each enabled provider.")),
    @RouterOperation(
        path = "/query",
        method = RequestMethod.POST,
        beanClass = LlmHandler.class,
        beanMethod = "query",
        operation =
            @Operation(
                operationId = "query",
                tags = "Inference",
                summary = "Single-turn LLM query",
                description =
                    "Sends a single prompt to the default (or specified) provider, with auto-failover on auth/config errors.",
                requestBody =
                    @RequestBody(
                        required = true,
                        content = @Content(schema = @Schema(implementation = LlmRequest.class))),
                responses =
                    @ApiResponse(
                        responseCode = "200",
                        content = @Content(schema = @Schema(implementation = LlmResponse.class))))),
    @RouterOperation(
        path = "/failover",
        method = RequestMethod.POST,
        beanClass = LlmHandler.class,
        beanMethod = "failoverQuery",
        operation =
            @Operation(
                operationId = "failoverQuery",
                tags = "Inference",
                summary = "Explicit multi-provider failover query",
                description =
                    "Tries each provider in the supplied (or default) chain in order until one succeeds.",
                requestBody =
                    @RequestBody(
                        required = true,
                        content = @Content(schema = @Schema(implementation = LlmRequest.class))),
                responses =
                    @ApiResponse(
                        responseCode = "200",
                        content = @Content(schema = @Schema(implementation = LlmResponse.class))))),
    @RouterOperation(
        path = "/chat",
        method = RequestMethod.POST,
        beanClass = LlmHandler.class,
        beanMethod = "chat",
        operation =
            @Operation(
                operationId = "chat",
                tags = "Inference",
                summary = "Multi-turn chat",
                description =
                    "Multi-turn chat backed by Redis-persisted conversation memory. 'session_id' is mandatory.",
                requestBody =
                    @RequestBody(
                        required = true,
                        content = @Content(schema = @Schema(implementation = LlmRequest.class))),
                responses =
                    @ApiResponse(
                        responseCode = "200",
                        content = @Content(schema = @Schema(implementation = LlmResponse.class))))),
    @RouterOperation(
        path = "/image",
        method = RequestMethod.POST,
        beanClass = ImageHandler.class,
        beanMethod = "generate",
        operation =
            @Operation(
                operationId = "generateImage",
                tags = "Inference",
                summary = "Generate an image",
                description =
                    "Generates one or more images from a text prompt via the configured image model.",
                requestBody =
                    @RequestBody(
                        required = true,
                        content =
                            @Content(
                                schema = @Schema(implementation = ImageGenerationRequest.class))),
                responses =
                    @ApiResponse(
                        responseCode = "200",
                        content =
                            @Content(
                                schema =
                                    @Schema(implementation = ImageGenerationResponse.class))))),
    @RouterOperation(
        path = "/embed",
        method = RequestMethod.POST,
        beanClass = EmbeddingHandler.class,
        beanMethod = "embed",
        operation =
            @Operation(
                operationId = "embed",
                tags = "Inference",
                summary = "Generate a text embedding",
                description =
                    "Generates a vector embedding for the given text using the OpenAI embedding model.")),
    @RouterOperation(
        path = "/openai/extract",
        method = RequestMethod.POST,
        beanClass = LlmHandler.class,
        beanMethod = "extractStructured",
        operation =
            @Operation(
                operationId = "extractStructured",
                tags = "Inference",
                summary = "Structured JSON extraction",
                description =
                    "Extracts a structured JSON object from the prompt response using Spring AI's entity converter.",
                requestBody =
                    @RequestBody(
                        required = true,
                        content = @Content(schema = @Schema(implementation = LlmRequest.class))))),
    @RouterOperation(
        path = "/{provider}/chat",
        method = RequestMethod.POST,
        beanClass = LlmHandler.class,
        beanMethod = "perProviderChat",
        operation =
            @Operation(
                operationId = "perProviderChat",
                tags = "Inference",
                summary = "Chat with an explicit provider",
                description =
                    "Routes the request to the exact provider in the path, bypassing auto-failover.",
                parameters = {
                  @Parameter(
                      name = "provider",
                      in = ParameterIn.PATH,
                      required = true,
                      description =
                          "Provider key, e.g. openai, anthropic, ollama, google, cohere, huggingface")
                },
                requestBody =
                    @RequestBody(
                        required = true,
                        content = @Content(schema = @Schema(implementation = LlmRequest.class))))),
    @RouterOperation(
        path = "/{provider}/stream",
        method = RequestMethod.POST,
        beanClass = LlmStreamHandler.class,
        beanMethod = "stream",
        operation =
            @Operation(
                operationId = "stream",
                tags = "Inference",
                summary = "Stream a chat response (SSE)",
                description =
                    "Server-Sent Events stream of response tokens, with the inbound guardrail chain applied before opening the stream.",
                parameters = {
                  @Parameter(
                      name = "provider",
                      in = ParameterIn.PATH,
                      required = true,
                      description = "Provider key, e.g. openai, anthropic, ollama")
                },
                requestBody =
                    @RequestBody(
                        required = true,
                        content = @Content(schema = @Schema(implementation = LlmRequest.class))))),
    @RouterOperation(
        path = "/sessions/{sessionId}",
        method = RequestMethod.DELETE,
        beanClass = LlmHandler.class,
        beanMethod = "deleteSession",
        operation =
            @Operation(
                operationId = "deleteSession",
                tags = "Sessions",
                summary = "Delete conversation history",
                description =
                    "Clears all Redis-stored conversation history for the given session id.",
                parameters = {
                  @Parameter(
                      name = "sessionId",
                      in = ParameterIn.PATH,
                      required = true,
                      description =
                          "Conversation/session id whose Redis-stored history will be cleared")
                }))
  })
  @Bean
  public RouterFunction<ServerResponse> llmRoutes(
      LlmHandler handler,
      LlmStreamHandler streamHandler,
      ImageHandler imageHandler,
      EmbeddingHandler embeddingHandler) {
    return RouterFunctions.route()
        // ── Public info ───────────────────────────────────────────────
        .GET("/health", handler::health)
        .GET("/providers", handler::providers)
        .GET("/models", handler::models)
        // ── LLM inference ─────────────────────────────────────────────
        .POST("/query", handler::query)
        .POST("/failover", handler::failoverQuery)
        .POST("/chat", handler::chat)
        .POST("/image", imageHandler::generate)
        .POST("/embed", embeddingHandler::embed)
        .POST("/openai/extract", handler::extractStructured)
        .POST("/{provider}/chat", handler::perProviderChat)
        .POST("/{provider}/stream", streamHandler::stream)
        // ── Session management ────────────────────────────────────────
        .DELETE("/sessions/{sessionId}", handler::deleteSession)
        .build();
  }
}
