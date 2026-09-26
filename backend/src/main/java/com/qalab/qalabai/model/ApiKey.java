package com.qalab.qalabai.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A per-account API key.
 *
 * <p>Only a salted hash is stored, so a database leak does not yield usable keys.
 * This mirrors the write-only convention {@link com.qalab.qalabai.ai.gateway.CredentialStore}
 * already applies to provider keys: the raw value is returned exactly once, at
 * creation, and is never recoverable afterwards.</p>
 *
 * <p>See {@code docs/adr/0001-authentication-and-tenancy.md}.</p>
 */
@Entity
@Table(name = "api_key", indexes = @Index(name = "idx_api_key_hash", columnList = "keyHash", unique = true))
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    /** Human label, e.g. "cli on laptop". Never used for authorization. */
    @Column(nullable = false, length = 120)
    private String label;

    /** Lowercase hex SHA-256 of the raw key, salted with {@link #salt}. */
    @Column(nullable = false, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 64)
    private String salt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime lastUsedAt;

    @Column
    private LocalDateTime revokedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
