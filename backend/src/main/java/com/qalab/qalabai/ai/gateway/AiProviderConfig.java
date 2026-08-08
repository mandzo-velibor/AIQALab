package com.qalab.qalabai.ai.gateway;

/**
 * Resolved provider configuration for a request or workflow. Never contains raw
 * credentials — API keys live only in the {@code CredentialStore} or server
 * config.
 */
public class AiProviderConfig {

    private final AiProviderType provider;
    private final String model;
    private final AiCredentialMode credentialMode;

    public AiProviderConfig(AiProviderType provider, String model, AiCredentialMode credentialMode) {
        this.provider = provider;
        this.model = model;
        this.credentialMode = credentialMode;
    }

    public AiProviderType getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public AiCredentialMode getCredentialMode() {
        return credentialMode;
    }

    public static AiProviderConfig managed(AiProviderType provider, String model) {
        return new AiProviderConfig(provider, model, AiCredentialMode.MANAGED);
    }

    public static AiProviderConfig byok(AiProviderType provider, String model) {
        return new AiProviderConfig(provider, model, AiCredentialMode.BYOK);
    }

    public static AiProviderConfig local(AiProviderType provider, String model) {
        return new AiProviderConfig(provider, model, AiCredentialMode.LOCAL);
    }
}
