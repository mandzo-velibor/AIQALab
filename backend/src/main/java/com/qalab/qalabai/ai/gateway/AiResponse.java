package com.qalab.qalabai.ai.gateway;

import java.math.BigDecimal;

/**
 * Normalized response from the {@link AiGateway}. Token counts and cost are
 * always populated; the {@code estimated} flag marks whether token counts were
 * estimated by the gateway rather than reported by the provider.
 */
public class AiResponse {

    private final String content;
    private final AiProviderType provider;
    private final String model;
    private final int inputTokens;
    private final int outputTokens;
    private final int totalTokens;
    private final boolean estimated;
    private final BigDecimal estimatedCost;
    private final String operationId;

    public AiResponse(String content,
                      AiProviderType provider,
                      String model,
                      int inputTokens,
                      int outputTokens,
                      boolean estimated,
                      BigDecimal estimatedCost,
                      String operationId) {
        this.content = content;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = inputTokens + outputTokens;
        this.estimated = estimated;
        this.estimatedCost = estimatedCost;
        this.operationId = operationId;
    }

    public String getContent() {
        return content;
    }

    public AiProviderType getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public boolean isEstimated() {
        return estimated;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public String getOperationId() {
        return operationId;
    }
}
