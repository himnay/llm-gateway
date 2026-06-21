package com.llm.gateway.llm_gateway.tools;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Spring AI tool definitions that can be injected into any ChatClient call.
 *
 * <p>Usage in service layer: chatClient.prompt() .tools(gatewayTools) .user(request.getPrompt())
 * .call().content();
 *
 * <p>The LLM decides autonomously when to invoke each tool based on the user prompt.
 */
@Component
public class GatewayTools {

  @Tool(
      description =
          "Get the current date and time in ISO-8601 format for a given timezone. "
              + "Use 'UTC' if no timezone is specified.")
  public String getCurrentDateTime(String timezone) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone != null ? timezone : "UTC");
    } catch (Exception e) {
      zone = ZoneId.of("UTC");
    }
    return ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  @Tool(
      description =
          "Get information about a specific LLM provider supported by this gateway, "
              + "including its available models and best use-cases. "
              + "Supported values: openai, anthropic, google, ollama, huggingface, cohere.")
  public String getProviderInfo(String provider) {
    if (provider == null) return "Provider name is required.";
    return switch (provider.toLowerCase().trim()) {
      case "openai" ->
          "OpenAI — Models: gpt-4o, gpt-4-turbo, gpt-3.5-turbo. "
              + "Best for general-purpose tasks, coding, and tool use.";
      case "anthropic" ->
          "Anthropic Claude — Models: claude-3-5-sonnet-20241022, claude-3-opus-20240229, claude-3-haiku-20240307. "
              + "Best for reasoning, long-context analysis, and safe AI applications.";
      case "google" ->
          "Google Gemini — Models: gemini-1.5-pro-latest, gemini-1.5-flash-latest. "
              + "Best for multimodal tasks (text + image) and large-context processing.";
      case "ollama" ->
          "Ollama (local) — Models: llama3.1, mistral, phi3, neural-chat. "
              + "Best for private/air-gapped deployments with no data leaving the network.";
      case "huggingface" ->
          "Hugging Face — Models: mistralai/Mistral-7B-Instruct-v0.1, meta-llama/Llama-2-7b-chat. "
              + "Best for open-source model experimentation and custom fine-tuned models.";
      case "cohere" ->
          "Cohere — Models: command-r-plus, command-r, command-light. "
              + "Best for enterprise RAG, search, and document summarisation.";
      default ->
          "Unknown provider '"
              + provider
              + "'. "
              + "Supported: openai, anthropic, google, ollama, huggingface, cohere.";
    };
  }

  @Tool(
      description =
          "Calculate the estimated cost in USD for a given number of input and output tokens "
              + "for a specific model. Returns a rough estimate for budgeting purposes.")
  public String estimateTokenCost(String model, int inputTokens, int outputTokens) {
    double inputCost, outputCost;
    switch (model.toLowerCase()) {
      case "gpt-4o" -> {
        inputCost = 0.005;
        outputCost = 0.015;
      }
      case "gpt-4-turbo" -> {
        inputCost = 0.01;
        outputCost = 0.03;
      }
      case "gpt-3.5-turbo" -> {
        inputCost = 0.0005;
        outputCost = 0.0015;
      }
      case "claude-3-5-sonnet-20241022" -> {
        inputCost = 0.003;
        outputCost = 0.015;
      }
      case "claude-3-opus-20240229" -> {
        inputCost = 0.015;
        outputCost = 0.075;
      }
      default -> {
        inputCost = 0.001;
        outputCost = 0.002;
      }
    }
    double totalCost = (inputTokens / 1000.0 * inputCost) + (outputTokens / 1000.0 * outputCost);
    return String.format(
        "Estimated cost for model '%s': $%.6f USD "
            + "(input=%d tokens @ $%.4f/1k, output=%d tokens @ $%.4f/1k)",
        model, totalCost, inputTokens, inputCost, outputTokens, outputCost);
  }
}
