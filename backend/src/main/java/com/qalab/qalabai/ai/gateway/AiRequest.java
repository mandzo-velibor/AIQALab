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
    /**
     * Version of the prompt template behind this call, e.g. {@code test-generator@a1b2c3d4e5f6}.
     *
     * <p>Added by B-035. Without it, an AI call and its output cannot be tied to the prompt
     * that produced them, so a prompt edit is indistinguishable from a provider or model
     * change when something regresses. It is deliberately not a metric tag: every prompt
     * revision would mint a new time series, and the value needed for debugging is in the
     * log line that carries the operation id.
     */
    private final String promptVersion;

    public AiRequest(AiOperation operation,
                     String systemPrompt,
                     String userPrompt,
                     String model,
                     AiProviderType provider,
                     AiCredentialMode credentialMode,
                     Integer maxOutputTokens,
                     ResponseValidator validator,
                     String promptVersion) {
        this.operation = operation;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.model = model;
        this.provider = provider;
        this.credentialMode = credentialMode;
        this.maxOutputTokens = maxOutputTokens;
        this.validator = validator;
        this.promptVersion = promptVersion;
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

    public String getPromptVersion() {
        return promptVersion;
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
        private String promptVersion;
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

        /** Records which revision of the prompt template this call is using. */
        public Builder promptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
            return this;
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
            return new AiRequest(operation, systemPrompt, userPrompt, model, provider, credentialMode,
                    maxOutputTokens, validator, promptVersion);
        }
    }
}
