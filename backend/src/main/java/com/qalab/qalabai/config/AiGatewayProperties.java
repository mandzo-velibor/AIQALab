package com.qalab.qalabai.config;

import com.qalab.qalabai.ai.gateway.AiCredentialMode;
import com.qalab.qalabai.ai.gateway.AiProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Gateway configuration. Managed providers are the ones the server itself is
 * configured with (via OPENCODE / OPENAI / GEMINI / OLLAMA keys); the account
 * allowance limit is config driven and defaults to 4000 tokens for FREE.
 */
@ConfigurationProperties(prefix = "qalab.ai")
public class AiGatewayProperties {

    private AiProviderType defaultProvider = AiProviderType.AIQALAB;
    private AiCredentialMode defaultCredentialMode = AiCredentialMode.MANAGED;
    private long freeMonthlyTokenLimit = 4000;
    private int maxRetries = 2;
    private long retryBackoffMs = 1500;
    private boolean rateLimitEnabled = false;
    private Map<String, ProviderEndpoint> providers = new HashMap<>();

    public AiProviderType getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(AiProviderType defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public AiCredentialMode getDefaultCredentialMode() {
        return defaultCredentialMode;
    }

    public void setDefaultCredentialMode(AiCredentialMode defaultCredentialMode) {
        this.defaultCredentialMode = defaultCredentialMode;
    }

    public long getFreeMonthlyTokenLimit() {
        return freeMonthlyTokenLimit;
    }

    public void setFreeMonthlyTokenLimit(long freeMonthlyTokenLimit) {
        this.freeMonthlyTokenLimit = freeMonthlyTokenLimit;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public Map<String, ProviderEndpoint> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderEndpoint> providers) {
        this.providers = providers;
    }

    public ProviderEndpoint endpoint(AiProviderType provider) {
        return providers.get(provider.name().toLowerCase());
    }

    /** Endpoint defaults (base URL, model) for a provider, overridable per account. */
    public static class ProviderEndpoint {

        private String baseUrl;
        private String model;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
