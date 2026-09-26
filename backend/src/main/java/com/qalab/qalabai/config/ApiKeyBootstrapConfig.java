package com.qalab.qalabai.config;

import com.qalab.qalabai.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * First-run API-key bootstrap (B-013).
 *
 * <p>When no key exists and one is required, a key is issued and printed to the log
 * <em>once</em>. Without this a fresh install configured with
 * {@code require-api-key=true} would be entirely unusable: there would be no way to
 * obtain the first credential. Trust-on-first-use, which is appropriate for a
 * self-hosted tool and is called out in ADR 0001.</p>
 *
 * <p>The value is only ever emitted here. It is not stored, not recoverable, and not
 * re-printed — if it is lost, revoke via the database or issue a new one.</p>
 */
@Configuration
public class ApiKeyBootstrapConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyBootstrapConfig.class);

    @Bean
    public CommandLineRunner bootstrapFirstApiKey(ApiKeyService apiKeyService,
                                                  @Value("${qalab.security.require-api-key:false}") boolean requireApiKey) {
        return args -> {
            if (apiKeyService.anyKeyExists()) {
                log.info("API keys already exist; skipping first-run bootstrap.");
                return;
            }

            if (!requireApiKey) {
                log.warn("""
                        SECURITY: no API key exists and qalab.security.require-api-key is false, \
                        so /api/** is currently UNAUTHENTICATED. Anyone who can reach this port can \
                        spend AI budget and read all project data. Set require-api-key=true and \
                        restart to enforce, or POST /api/v1/account/api-keys to issue a key (issuing \
                        one also switches enforcement on).""");
                return;
            }

            ApiKeyService.IssuedKey issued = apiKeyService.issue(null, "bootstrap");
            log.warn("""

                    ════════════════════════════════════════════════════════════════════
                     API key issued for first run (printed once, never stored):

                       {}
                       label: {}

                     Use it as:  curl -H "Authorization: Bearer {}" ...
                     Or set:     QALAB_API_KEY={} in your environment / .qalab.json
                    ════════════════════════════════════════════════════════════════════
                    """,
                    issued.rawKey(), issued.label(), issued.rawKey(), issued.rawKey());
        };
    }
}
