package com.qalab.qalabai.ai.gateway;

/**
 * Result of a low-level provider call, including token usage when the provider
 * reports it (estimated flag false) or as estimated by the gateway.
 *
 * <p>For a client that internally cascades (the managed path tries several models),
 * {@code attempts} is how many upstream calls actually happened and the token counts
 * are the <em>sum across all of them</em>. That matters because the rejected responses
 * were still paid for: reporting only the winning response's tokens understates the
 * cost by the number of attempts, which is precisely what makes a failing free tier
 * look cheap right up until it runs out.</p>
 */
public class ProviderCallResult {

    private final String content;
    private final int inputTokens;
    private final int outputTokens;
    private final boolean estimated;
    private final String modelUsed;
    private final int attempts;

    public ProviderCallResult(String content, int inputTokens, int outputTokens,
                              boolean estimated, String modelUsed) {
        this(content, inputTokens, outputTokens, estimated, modelUsed, 1);
    }

    public ProviderCallResult(String content, int inputTokens, int outputTokens,
                              boolean estimated, String modelUsed, int attempts) {
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimated = estimated;
        this.modelUsed = modelUsed;
        this.attempts = Math.max(1, attempts);
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

    /**
     * Upstream calls this result cost. Greater than 1 when the client cascaded, in
     * which case the token counts already include every attempt.
     */
    public int getAttempts() {
        return attempts;
    }
}
