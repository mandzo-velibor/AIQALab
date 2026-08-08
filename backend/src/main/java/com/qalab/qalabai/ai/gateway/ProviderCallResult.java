package com.qalab.qalabai.ai.gateway;

/**
 * Result of a low-level provider call, including token usage when the provider
 * reports it (estimated flag false) or as estimated by the gateway.
 */
public class ProviderCallResult {

    private final String content;
    private final int inputTokens;
    private final int outputTokens;
    private final boolean estimated;
    private final String modelUsed;

    public ProviderCallResult(String content, int inputTokens, int outputTokens,
                              boolean estimated, String modelUsed) {
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimated = estimated;
        this.modelUsed = modelUsed;
    }

    public String getContent() {
        return content;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getTotalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean isEstimated() {
        return estimated;
    }

    public String getModelUsed() {
        return modelUsed;
    }
}
