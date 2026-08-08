package com.qalab.qalabai.ai.gateway;

/**
 * Heuristic token estimator used when a provider does not report usage. Roughly
 * 4 characters per token for prompts and responses. Estimates are always marked
 * as {@code estimated} on usage records.
 */
public class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    public static int estimateInputTokens(String systemPrompt, String userPrompt) {
        int chars = (systemPrompt == null ? 0 : systemPrompt.length())
                + (userPrompt == null ? 0 : userPrompt.length());
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }

    public static int estimateOutputTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / CHARS_PER_TOKEN);
    }
}
