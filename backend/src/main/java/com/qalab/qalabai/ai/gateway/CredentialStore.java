package com.qalab.qalabai.ai.gateway;

import java.util.Optional;
import java.util.Set;

/**
 * Stores provider API keys. Keys are write-only from the API's perspective:
 * they can be stored, checked and deleted, but the stored value is never
 * returned to callers, never logged and never appears in database queries,
 * exceptions or the frontend.
 */
public interface CredentialStore {

    void store(AiProviderType provider, String apiKey);

    Optional<String> get(AiProviderType provider);

    boolean has(AiProviderType provider);

    void delete(AiProviderType provider);

    /** Providers with a credential stored for BYOK use. */
    Set<AiProviderType> configuredProviders();
}
