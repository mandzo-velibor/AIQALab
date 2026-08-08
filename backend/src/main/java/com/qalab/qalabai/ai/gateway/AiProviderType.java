package com.qalab.qalabai.ai.gateway;

/**
 * Supported AI provider types. Extensible — providers are configured, never
 * hard-coded into agents.
 */
public enum AiProviderType {

    AIQALAB,
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    OPENCODE,
    OLLAMA;

    public static AiProviderType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        try {
            return AiProviderType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported AI provider: " + value);
        }
    }
}
