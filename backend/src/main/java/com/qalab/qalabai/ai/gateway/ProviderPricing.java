package com.qalab.qalabai.ai.gateway;

import java.math.BigDecimal;

/**
 * Cost model for a provider+model pair, in USD per token. {@code null} means
 * the price is unknown — the gateway reports {@code estimatedCost = null} rather
 * than inventing a value.
 */
public class ProviderPricing {

    private final AiProviderType provider;
    private final String model;
    private final BigDecimal inputCostPerToken;
    private final BigDecimal outputCostPerToken;

    public ProviderPricing(AiProviderType provider, String model,
                           BigDecimal inputCostPerToken, BigDecimal outputCostPerToken) {
        this.provider = provider;
        this.model = model;
        this.inputCostPerToken = inputCostPerToken;
        this.outputCostPerToken = outputCostPerToken;
    }

    public AiProviderType getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public BigDecimal getInputCostPerToken() {
        return inputCostPerToken;
    }

    public BigDecimal getOutputCostPerToken() {
        return outputCostPerToken;
    }

    /** True when pricing for this provider+model is known. */
    public boolean isKnown() {
        return inputCostPerToken != null && outputCostPerToken != null;
    }

    public BigDecimal estimateCost(int inputTokens, int outputTokens) {
        if (!isKnown()) {
            return null;
        }
        BigDecimal input = inputCostPerToken.multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal output = outputCostPerToken.multiply(BigDecimal.valueOf(outputTokens));
        return input.add(output);
    }
}
