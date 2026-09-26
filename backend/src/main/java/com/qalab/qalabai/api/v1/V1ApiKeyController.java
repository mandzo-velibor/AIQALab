package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.model.ApiKey;
import com.qalab.qalabai.service.AccountService;
import com.qalab.qalabai.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * API-key lifecycle for the authenticated account (B-013).
 *
 * <p>Key creation is intentionally reachable while unauthenticated-protected: an
 * operator with no key yet must be able to mint the first one. Once a key exists the
 * filter enforces {@code /api/**}, so this endpoint is itself protected from then on —
 * a bootstrap key cannot be used to mint more keys without the key already being
 * known, which is the intended trust-on-first-use model.</p>
 *
 * <p>See {@code docs/adr/0001-authentication-and-tenancy.md}.</p>
 */
@RestController
@RequestMapping("/api/v1/account/api-keys")
public class V1ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(V1ApiKeyController.class);

    private final ApiKeyService apiKeyService;
    private final AccountService accountService;

    public V1ApiKeyController(ApiKeyService apiKeyService, AccountService accountService) {
        this.apiKeyService = apiKeyService;
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<CreatedKey> create(@RequestBody(required = false) CreateKeyRequest request) {
        String label = request == null ? null : request.label();
        ApiKeyService.IssuedKey issued = apiKeyService.issue(accountService.defaultAccount().getId(), label);
        log.info("POST /api/v1/account/api-keys issued id={} label={}", issued.id(), issued.label());
        return ResponseEntity.ok(new CreatedKey(issued.id(), issued.label(), issued.rawKey(), issued.createdAt()));
    }

    @GetMapping
    public ResponseEntity<List<KeyView>> list() {
        Long accountId = accountService.defaultAccount().getId();
        List<KeyView> views = apiKeyService.listForAccount(accountId).stream().map(KeyView::from).toList();
        return ResponseEntity.ok(views);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable Long id) {
        Long accountId = accountService.defaultAccount().getId();
        boolean revoked = apiKeyService.revoke(id, accountId);
        if (!revoked) {
            throw ApiException.invalidRequest("No active API key with id " + id + " for this account");
        }
        return ResponseEntity.ok(Map.of("revoked", true, "id", id));
    }

    public record CreateKeyRequest(String label) {
    }

    /**
     * The raw key is present exactly once, here. It is not stored and cannot be
     * retrieved again.
     */
    public record CreatedKey(Long id, String label, String key, LocalDateTime createdAt) {
    }

    /** A key as listed: never includes the secret. */
    public record KeyView(Long id, String label, LocalDateTime createdAt,
                          LocalDateTime lastUsedAt, boolean active) {
        static KeyView from(ApiKey k) {
            return new KeyView(k.getId(), k.getLabel(), k.getCreatedAt(), k.getLastUsedAt(), k.isActive());
        }
    }
}
