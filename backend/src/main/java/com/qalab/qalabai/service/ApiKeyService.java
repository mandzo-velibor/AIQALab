package com.qalab.qalabai.service;

import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.model.ApiKey;
import com.qalab.qalabai.ai.gateway.Plan;
import com.qalab.qalabai.ai.gateway.BudgetPolicy;
import com.qalab.qalabai.repository.AccountRepository;
import com.qalab.qalabai.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Issues and verifies per-account API keys.
 *
 * <p>Only a salted SHA-256 hash is persisted. The raw key is returned exactly once, at
 * creation, and cannot be recovered afterwards — the same write-only guarantee
 * {@link com.qalab.qalabai.ai.gateway.CredentialStore} gives provider keys.
 *
 * <p>See {@code docs/adr/0001-authentication-and-tenancy.md}.</p>
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /** Prefixed so a leaked key is identifiable in logs and pastebins. */
    private static final String KEY_PREFIX = "qalab_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final AccountRepository accountRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, AccountRepository accountRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * The account that owns a key when the caller did not name one.
     *
     * <p>Creates a default account if none exists yet. Doing this here rather than
     * relying on {@code AccountService}'s startup runner removes a real ordering
     * dependency: the API-key bootstrap runs on a fresh database too, and if it
     * depended on another runner having created the account first the whole context
     * would fail to start on a clean install.</p>
     */
    private Account defaultAccount() {
        return accountRepository.findFirstByOrderByIdAsc().orElseGet(() -> {
            Account created = new Account();
            created.setName("default");
            created.setPlan(Plan.FREE);
            created.setBudgetPolicy(BudgetPolicy.defaultFor(Plan.FREE));
            Account saved = accountRepository.save(created);
            log.info("Created default account {} to own API keys", saved.getId());
            return saved;
        });
    }

    /** A newly created key: the id, and the raw value which is never stored. */
    public record IssuedKey(Long id, String label, String rawKey, LocalDateTime createdAt) {
    }

    @Transactional
    public IssuedKey issue(Long accountId, String label) {
        Account account = accountId == null
                ? defaultAccount()
                : accountRepository.findById(accountId)
                        .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        String raw = generateRawKey();
        ApiKey key = new ApiKey();
        key.setAccountId(account.getId());
        key.setLabel(label == null || label.isBlank() ? "api-key" : label.trim());
        key.setSalt(generateSalt());
        key.setKeyHash(hash(raw, key.getSalt()));
        ApiKey saved = apiKeyRepository.save(key);

        log.info("Issued API key id={} account={} label={} (raw value is not stored)",
                saved.getId(), account.getId(), saved.getLabel());
        return new IssuedKey(saved.getId(), saved.getLabel(), raw, saved.getCreatedAt());
    }

    /**
     * Resolves a raw key to its owning account id, or empty when unknown or revoked.
     *
     * <p>Every stored key is tried, because the hash is salted per key and therefore
     * not searchable by a single indexed lookup. The candidate set is small (a handful
     * of keys per account) and this is not a hot path relative to an LLM call.</p>
     */
    @Transactional
    public Optional<Long> resolveAccountId(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        String candidate = rawKey.trim();
        for (ApiKey key : apiKeyRepository.findAll()) {
            if (!key.isActive()) {
                continue;
            }
            if (constantTimeEquals(key.getKeyHash(), hash(candidate, key.getSalt()))) {
                key.setLastUsedAt(LocalDateTime.now());
                apiKeyRepository.save(key);
                return Optional.of(key.getAccountId());
            }
        }
        return Optional.empty();
    }

    public List<ApiKey> listForAccount(Long accountId) {
        return apiKeyRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    public boolean anyKeyExists() {
        return apiKeyRepository.count() > 0;
    }

    @Transactional
    public boolean revoke(Long id, Long accountId) {
        return apiKeyRepository.findById(id)
                .filter(k -> k.getAccountId().equals(accountId))
                .filter(ApiKey::isActive)
                .map(k -> {
                    k.setRevokedAt(LocalDateTime.now());
                    apiKeyRepository.save(k);
                    return true;
                })
                .orElse(false);
    }

    /** Generates a 256-bit random key. */
    static String generateRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    private static String generateSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String hash(String rawKey, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] out = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Length-independent comparison, so a wrong prefix cannot be probed by timing. */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
