package com.qalab.qalabai.ai.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Holds the server-managed (MANAGED mode) API keys read from configuration.
 * BYOK/LOCAL credentials never touch this class — they live in the
 * {@link CredentialStore}. Keys are never logged or exposed.
 */
@Component
public class ManagedCredentials {

    @Value("${opencode.go.api-key:}")
    private String goApiKey;

    @Value("${opencode.zen.api-key:}")
    private String zenApiKey;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ollama.api-key:}")
    private String ollamaApiKey;

    public Optional<String> keyFor(AiProviderType provider) {
        switch (provider) {
            case AIQALAB, OPENCODE:
                String go = goApiKey == null ? "" : goApiKey;
                String zen = zenApiKey == null ? "" : zenApiKey;
                if (!go.isBlank()) return Optional.of(go);
                if (!zen.isBlank()) return Optional.of(zen);
                return Optional.empty();
            case OPENAI:
                return Optional.ofNullable(blankToNull(openAiApiKey));
            case GOOGLE:
                return Optional.ofNullable(blankToNull(geminiApiKey));
            case OLLAMA:
                return Optional.ofNullable(blankToNull(ollamaApiKey));
            case ANTHROPIC:
            default:
                return Optional.empty();
        }
    }

    /** Whether the server has at least one managed key for this provider. */
    public boolean isManaged(AiProviderType provider) {
        return keyFor(provider).isPresent();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
