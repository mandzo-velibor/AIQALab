package com.qalab.qalabai.ai.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory credential store for this sprint. Keys are stored obfuscated
 * (Base64) in memory only — never in the database, never logged. A real
 * encrypted/persistent store can swap in behind the same interface.
 */
@Component
public class InMemoryCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCredentialStore.class);

    private final Map<AiProviderType, String> keys = new ConcurrentHashMap<>();

    @Override
    public void store(AiProviderType provider, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        keys.put(provider, Base64.getEncoder().encodeToString(apiKey.getBytes()));
        log.info("Stored BYOK credential for provider {} (key stored, never logged)", provider);
    }

    @Override
    public Optional<String> get(AiProviderType provider) {
        String encoded = keys.get(provider);
        if (encoded == null) {
            return Optional.empty();
        }
        return Optional.of(new String(Base64.getDecoder().decode(encoded)));
    }

    @Override
    public boolean has(AiProviderType provider) {
        return keys.containsKey(provider);
    }

    @Override
    public void delete(AiProviderType provider) {
        keys.remove(provider);
        log.info("Deleted BYOK credential for provider {}", provider);
    }

    @Override
    public Set<AiProviderType> configuredProviders() {
        return Set.copyOf(keys.keySet());
    }
}
