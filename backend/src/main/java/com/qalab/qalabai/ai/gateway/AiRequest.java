package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.ai.provider.ResponseValidator;

/**
 * Normalized request sent through the {@link AiGateway}. Providers are never
 * exposed to agents directly — all AI access flows through this type.
 */
public class AiRequest {

    private final AiOperation operation;
    private final String systemPrompt;
    private final String userPrompt;
    private final String model;
    private final AiProviderType provider;
    private final AiCredentialMode credentialMode;
    private final Integer maxOutputTokens;
    private final ResponseValidator validator;

    public AiRequest(AiOperation operation,
                     String systemPrompt,
                     String userPrompt,
                     String model,
                     AiProviderType provider,
                     AiCredentialMode credentialMode,
                     Integer maxOutputTokens,
                     ResponseValidator validator) {
        this.operation = operation;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.model = model;
        this.provider = provider;
        this.credentialMode = credentialMode;
        this.maxOutputTokens = maxOutputTokens;
        this.validator = validator;
    }

    public AiOperation getOperation() {
        return operation;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public String getModel() {
        return model;
    }

    public AiProviderType getProvider() {
        return provider;
    }

    public AiCredentialMode getCredentialMode() {
        return credentialMode;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public ResponseValidator getValidator() {
        return validator;
    }

    public static Builder builder(AiOperation operation, String systemPrompt, String userPrompt) {
        return new Builder(operation, systemPrompt, userPrompt);
    }

    public static class Builder {

        private final AiOperation operation;
        private final String systemPrompt;
        private final String userPrompt;
        private String model;
        private AiProviderType provider;
        private AiCredentialMode credentialMode = AiCredentialMode.MANAGED;
        private Integer maxOutputTokens;
        private ResponseValidator validator;

        private Builder(AiOperation operation, String systemPrompt, String userPrompt) {
            this.operation = operation;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder provider(AiProviderType provider) {
            this.provider = provider;
            return this;
        }

        public Builder credentialMode(AiCredentialMode credentialMode) {
            this.credentialMode = credentialMode;
            return this;
        }

        public Builder maxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder validator(ResponseValidator validator) {
            this.validator = validator;
            return this;
        }

        public AiRequest build() {
            return new AiRequest(operation, systemPrompt, userPrompt, model, provider, credentialMode, maxOutputTokens, validator);
        }
    }
}
