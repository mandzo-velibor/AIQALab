package com.qalab.qalabai.security;

import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.model.ApiKey;
import com.qalab.qalabai.service.ApiKeyService;

/**
 * The authenticated caller, resolved from a bearer API key.
 *
 * <p>Deliberately a small interface-like record rather than a JWT subject, so an
 * OIDC/JWT implementation can be added later without touching controllers: the rest
 * of the application only ever sees an account id.</p>
 *
 * <p>See {@code docs/adr/0001-authentication-and-tenancy.md}.</p>
 */
public record AuthPrincipal(Long accountId, String plan, Long apiKeyId) {

    public static AuthPrincipal of(Account account, ApiKey key) {
        return new AuthPrincipal(account.getId(), account.getPlan().name(), key.getId());
    }
}
