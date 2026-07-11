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

    /**
     * The unique identifier for this refresh token record.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The id of the user this refresh token belongs to.
     */
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

    /**
     * The instant at which this token expires and can no longer be exchanged.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The instant this token was revoked, or {@code null} if it is still active. A token is revoked
     * on rotation, logout, lockout, or family-wide reuse detection.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * The instant this record was created. Managed by the database (DEFAULT CURRENT_TIMESTAMP).
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * The instant this token was last exchanged (rotated), or {@code null} if it never has been.
     */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * Protected no-argument constructor required by JPA.
     */
    protected RefreshToken() {
        // Required by JPA.
    }

    /**
     * Constructs a new refresh token record for the given user and family.
     *
     * @param userId    the id of the user the token belongs to
     * @param familyId  the family identifier shared by all tokens rotated from the same login
     * @param tokenHash the SHA-256 hex hash of the raw token
     * @param expiresAt the instant the token expires
     */
    public RefreshToken(Integer userId, String familyId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /**
     * Whether this token is currently usable: not revoked and not yet expired as of {@code now}.
     *
     * @param now the instant to evaluate against
     * @return {@code true} if the token is neither revoked nor expired
     */
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

    /**
     * Records that this token was just used (exchanged/rotated) by setting {@code lastUsedAt} to now.
     */
    public void markUsed() {
        this.lastUsedAt = Instant.now();
    }

    /**
     * Returns the unique identifier of this refresh token record.
     *
     * @return the record id
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the id of the user this token belongs to.
     *
     * @return the user id
     */
    public Integer getUserId() {
        return userId;
    }

    /**
     * Returns the family identifier shared by all tokens rotated from the same login.
     *
     * @return the family id
     */
    public String getFamilyId() {
        return familyId;
    }

    /**
     * Returns the SHA-256 hex hash of the raw token.
     *
     * @return the token hash
     */
    public String getTokenHash() {
        return tokenHash;
    }

    /**
     * Returns the instant this token expires.
     *
     * @return the expiry timestamp
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Returns the instant this token was revoked, or {@code null} if it is still active.
     *
     * @return the revocation timestamp, or {@code null}
     */
    public Instant getRevokedAt() {
        return revokedAt;
    }

    /**
     * Returns the instant this record was created.
     *
     * @return the creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the instant this token was last used (rotated), or {@code null} if it never has been.
     *
     * @return the last-used timestamp, or {@code null}
     */
    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
