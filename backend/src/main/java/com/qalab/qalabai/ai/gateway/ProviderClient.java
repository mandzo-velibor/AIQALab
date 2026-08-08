package com.qalab.qalabai.ai.gateway;

/**
 * Internal low-level provider client used by the {@link AiGateway}. Each
 * client knows how to call one provider with explicit credentials and returns
 * content plus token usage. Clients never perform budget or credential logic.
 */
public interface ProviderClient {

    AiProviderType type();

    ProviderCallResult call(ProviderCallRequest request);
}
