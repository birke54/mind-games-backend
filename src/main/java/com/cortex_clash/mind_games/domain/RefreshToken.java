package com.cortex_clash.mind_games.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A server-side record of an issued refresh token. Only the SHA-256 hash of the raw token is
 * stored. Tokens rotated from the same login share a {@code familyId} so that reuse of a
 * superseded token can revoke the entire lineage.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    /**
     * Identifier shared by every token rotated from a single login. Reuse of a superseded token
     * revokes the whole family, so this is the unit of theft-detection revocation.
     */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    /**
     * SHA-256 hash (hex) of the raw token. The raw token value is never persisted; lookups hash the
     * presented value and match against this column.
     */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The instant this token was revoked, or {@code null} if it is still active. A token is revoked
     * on rotation, logout, lockout, or family-wide reuse detection.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Managed by the database (DEFAULT CURRENT_TIMESTAMP). */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected RefreshToken() {
        // Required by JPA.
    }

    public RefreshToken(Integer userId, String familyId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Whether this token is currently usable: not revoked and not yet expired as of {@code now}. */
    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Marks this token as revoked. Idempotent: if the token is already revoked, the original
     * revocation time is preserved.
     */
    public void revoke() {
        if (this.revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    public void markUsed() {
        this.lastUsedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
