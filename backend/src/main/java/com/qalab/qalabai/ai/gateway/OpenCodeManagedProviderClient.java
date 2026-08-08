package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.ai.opencode.OpenCodeAiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts the existing OpenCode managed provider (Go → Zen → Gemini → Ollama
 * fallback chain) to the {@link ProviderClient} contract. This is the default
 * AIQALAB managed path — it reuses the fallback logic without duplicating it.
 */
public class OpenCodeManagedProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeManagedProviderClient.class);

    private final OpenCodeAiProvider delegate;

    public OpenCodeManagedProviderClient(OpenCodeAiProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public AiProviderType type() {
        return AiProviderType.AIQALAB;
    }

    @Override
    public ProviderCallResult call(ProviderCallRequest request) {
        try {
            String content = delegate.chat(request.getSystemPrompt(), request.getUserPrompt(), request.getValidator());
            log.info("OpenCode managed response received, length: {} chars", content.length());
            int input = TokenEstimator.estimateInputTokens(request.getSystemPrompt(), request.getUserPrompt());
            int output = TokenEstimator.estimateOutputTokens(content);
            return new ProviderCallResult(content, input, output, true, "AIQALAB-managed");
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
