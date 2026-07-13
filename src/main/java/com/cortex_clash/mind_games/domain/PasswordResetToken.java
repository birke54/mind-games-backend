package com.cortex_clash.mind_games.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A server-side record of an issued password-reset token. As with {@link RefreshToken}, only the
 * SHA-256 hash of the raw token is stored — the raw value exists nowhere but the email that carried
 * it, and cannot be recovered from the database.
 *
 * <p>Unlike a refresh token these are single-use and short-lived: redemption stamps
 * {@code usedAt}, and issuing a new one stamps it on any token still outstanding for that user, so
 * a user only ever has one live reset link at a time.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    /**
     * The unique identifier for this reset token record.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The id of the user this reset token belongs to.
     */
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    /**
     * SHA-256 hash (hex) of the raw token. The raw token value is never persisted; lookups hash the
     * presented value and match against this column.
     */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /**
     * The instant at which this token expires and can no longer be redeemed.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The instant this token was consumed, or {@code null} if it is still outstanding. Set on
     * redemption, and on reissue to invalidate a superseded token. Either way the token is dead.
     */
    @Column(name = "used_at")
    private Instant usedAt;

    /**
     * The instant this record was created. Managed by the database (DEFAULT CURRENT_TIMESTAMP).
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Protected no-argument constructor required by JPA.
     */
    protected PasswordResetToken() {
        // Required by JPA.
    }

    /**
     * Constructs a new reset token record for the given user.
     *
     * @param userId    the id of the user the token belongs to
     * @param tokenHash the SHA-256 hex hash of the raw token
     * @param expiresAt the instant the token expires
     */
    public PasswordResetToken(Integer userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /**
     * Whether this token can still be redeemed as of {@code now}: unused and unexpired.
     *
     * @param now the instant to evaluate against
     * @return {@code true} if the token is neither used nor expired
     */
    public boolean isRedeemable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Marks this token as consumed. Idempotent: if it was already used, the original time stands.
     */
    public void markUsed() {
        if (this.usedAt == null) {
            this.usedAt = Instant.now();
        }
    }

    /**
     * Returns the unique identifier of this reset token record.
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
     * Returns the instant this token was consumed, or {@code null} if it is still outstanding.
     *
     * @return the used timestamp, or {@code null}
     */
    public Instant getUsedAt() {
        return usedAt;
    }

    /**
     * Returns the instant this record was created.
     *
     * @return the creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
