package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.ai.provider.ResponseValidator;

/**
 * A single low-level provider call with explicit credentials. The gateway
 * resolves configuration and credentials before building this; clients never
 * read server config themselves.
 */
public class ProviderCallRequest {

    private final String systemPrompt;
    private final String userPrompt;
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final Integer maxOutputTokens;
    private final ResponseValidator validator;

    public ProviderCallRequest(String systemPrompt, String userPrompt, String model,
                               String apiKey, String baseUrl, Integer maxOutputTokens,
                               ResponseValidator validator) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.maxOutputTokens = maxOutputTokens;
        this.validator = validator;
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

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public ResponseValidator getValidator() {
        return validator;
    }
}
