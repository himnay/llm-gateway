package com.llm.gateway.llm_gateway.cost;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class TokenCostService {

    // Approximate pricing per 1M tokens (USD) as of mid-2025
    private static final Map<String, double[]> PRICE_PER_1M = Map.of(
        "gpt-4o",            new double[]{5.00, 15.00},
        "gpt-4o-mini",       new double[]{0.15,  0.60},
        "claude-sonnet-4-6", new double[]{3.00, 15.00},
        "claude-haiku-4-5",  new double[]{0.25,  1.25},
        "claude-opus-4-8",   new double[]{15.0,  75.0},
        "gpt-4-turbo",       new double[]{10.0,  30.0}
    );

    /**
     * Returns estimated cost in USD for a single LLM call.
     *
     * @param model        model identifier (partial match is supported)
     * @param inputTokens  prompt token count
     * @param outputTokens completion token count
     */
    public double estimateCost(String model, int inputTokens, int outputTokens) {
        double[] prices = PRICE_PER_1M.entrySet().stream()
                .filter(e -> model != null && model.toLowerCase().contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(new double[]{5.00, 15.00}); // default to gpt-4o pricing
        double cost = (inputTokens / 1_000_000.0 * prices[0]) + (outputTokens / 1_000_000.0 * prices[1]);
        log.debug("Cost estimate: model={} input={} output={} cost={}",
                model, inputTokens, outputTokens, String.format("%.6f", cost));
        return cost;
    }

    public record CostSummary(String model, int inputTokens, int outputTokens, double estimatedCostUsd) {}

    public CostSummary summarize(String model, int inputTokens, int outputTokens) {
        return new CostSummary(model, inputTokens, outputTokens, estimateCost(model, inputTokens, outputTokens));
    }
}
