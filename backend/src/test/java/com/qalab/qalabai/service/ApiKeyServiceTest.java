package com.qalab.qalabai.service;

import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.model.ApiKey;
import com.qalab.qalabai.repository.AccountRepository;
import com.qalab.qalabai.repository.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Keys are stored as salted hashes so a database leak does not yield usable
 * credentials, mirroring the write-only guarantee CredentialStore already gives
 * provider keys. The raw value exists only in the IssuedKey returned at creation.
 */
class ApiKeyServiceTest {

    /** Stands in for the api_key table. */
    private List<ApiKey> table;
    private ApiKeyRepository apiKeyRepository;
    private AccountRepository accountRepository;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        table = new ArrayList<>();
        apiKeyRepository = mock(ApiKeyRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new ApiKeyService(apiKeyRepository, accountRepository);

        Account account = new Account();
        account.setId(1L);
        when(accountRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(account));
        when(accountRepository.findById(any())).thenReturn(Optional.of(account));

        when(apiKeyRepository.save(any())).thenAnswer(inv -> {
            ApiKey key = inv.getArgument(0);
            if (key.getId() == null) {
                key.setId((long) table.size() + 1);
            }
            table.removeIf(existing -> existing.getId().equals(key.getId()));
            table.add(key);
            return key;
        });
        when(apiKeyRepository.findAll()).thenAnswer(inv -> new ArrayList<>(table));
        when(apiKeyRepository.count()).thenAnswer(inv -> (long) table.size());
        when(apiKeyRepository.findById(any())).thenAnswer(inv ->
                table.stream().filter(k -> k.getId().equals(inv.getArgument(0))).findFirst());
        when(apiKeyRepository.findByAccountIdOrderByCreatedAtDesc(any())).thenAnswer(inv ->
                table.stream().filter(k -> k.getAccountId().equals(inv.<Long>getArgument(0))).toList());
    }

    private ApiKey stored() {
        return table.get(table.size() - 1);
    }

    @Test
    void anIssuedKeyIsPrefixedLongAndUnpredictable() {
        String raw = ApiKeyService.generateRawKey();

        assertTrue(raw.startsWith("qalab_"), "a leaked key should be identifiable: " + raw);
        assertEquals("qalab_".length() + 64, raw.length(), "expected 32 bytes of hex after the prefix");
        assertNotEquals(raw, ApiKeyService.generateRawKey(), "keys must not repeat");
    }

    @Test
    void theRawKeyIsNeverPersisted() {
        ApiKeyService.IssuedKey issued = service.issue(null, "cli");

        assertNotEquals(issued.rawKey(), stored().getKeyHash(),
                "the raw key must never be what is stored");
        assertNotEquals(issued.rawKey(), stored().getSalt());
        assertNull(stored().getRevokedAt());
        assertEquals("cli", stored().getLabel());
        assertEquals(64, stored().getKeyHash().length(), "expected a hex SHA-256");
    }

    @Test
    void aCorrectKeyResolvesToItsAccount() {
        ApiKeyService.IssuedKey issued = service.issue(null, "cli");

        assertEquals(Optional.of(1L), service.resolveAccountId(issued.rawKey()));
    }

    @Test
    void wrongKeysDoNotResolve() {
        service.issue(null, "cli");

        assertTrue(service.resolveAccountId("qalab_wrong").isEmpty());
        assertTrue(service.resolveAccountId("").isEmpty());
        assertTrue(service.resolveAccountId("   ").isEmpty());
        assertTrue(service.resolveAccountId(null).isEmpty());
    }

    @Test
    void keysAreSaltedSoIdenticalKeysHashDifferently() {
        // Two keys with the same raw value must not produce the same hash, or the
        // hashes leak that they are equal.
        String raw = "qalab_same";

        assertNotEquals(ApiKeyService.hash(raw, "aaaa"), ApiKeyService.hash(raw, "bbbb"));
    }

    @Test
    void everyIssuedKeyResolvesIndependently() {
        ApiKeyService.IssuedKey first = service.issue(null, "cli");
        ApiKeyService.IssuedKey second = service.issue(null, "laptop");

        assertEquals(Optional.of(1L), service.resolveAccountId(first.rawKey()));
        assertEquals(Optional.of(1L), service.resolveAccountId(second.rawKey()));
        assertNotEquals(stored().getSalt(), table.get(0).getSalt(), "salts must differ per key");
    }

    @Test
    void aRevokedKeyStopsResolving() {
        ApiKeyService.IssuedKey issued = service.issue(null, "cli");
        Long id = stored().getId();

        assertTrue(service.revoke(id, 1L));
        assertTrue(service.resolveAccountId(issued.rawKey()).isEmpty(),
                "a revoked key must not authenticate");
    }

    @Test
    void revokingIsScopedToTheOwningAccount() {
        ApiKeyService.IssuedKey issued = service.issue(null, "cli");
        Long id = stored().getId();

        assertFalse(service.revoke(id, 999L), "another account must not be able to revoke this key");
        assertEquals(Optional.of(1L), service.resolveAccountId(issued.rawKey()),
                "a cross-account revoke attempt must leave the key working");
    }

    @Test
    void revokingAnUnknownOrAlreadyRevokedKeyIsANoOp() {
        assertFalse(service.revoke(1234L, 1L));

        ApiKeyService.IssuedKey issued = service.issue(null, "cli");
        Long id = stored().getId();

        assertTrue(service.revoke(id, 1L));
        assertFalse(service.revoke(id, 1L), "revoking twice must not report success");
        assertTrue(service.resolveAccountId(issued.rawKey()).isEmpty());
    }

    @Test
    void aBlankLabelFallsBackToADefault() {
        service.issue(null, "   ");

        assertEquals("api-key", stored().getLabel());
    }

    @Test
    void constantTimeComparisonHandlesNullsAndLengths() {
        assertFalse(ApiKeyService.constantTimeEquals(null, "a"));
        assertFalse(ApiKeyService.constantTimeEquals("a", null));
        assertTrue(ApiKeyService.constantTimeEquals("abc", "abc"));
        assertFalse(ApiKeyService.constantTimeEquals("abc", "abd"));
        assertFalse(ApiKeyService.constantTimeEquals("abc", "abcdef"));
    }

    @Test
    void issuingWithoutAnyAccountFailsLoudly() {
        when(accountRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.issue(null, "cli"));
    }

    @Test
    void anyKeyExistsReflectsWhetherAnyKeyWasIssued() {
        assertFalse(service.anyKeyExists());
        service.issue(null, "cli");
        assertTrue(service.anyKeyExists());
    }

    @Test
    void listForAccountReturnsOnlyThatAccountsKeys() {
        service.issue(null, "cli");

        assertEquals(1, service.listForAccount(1L).size());
    }
}
