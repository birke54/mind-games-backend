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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    /**
     * SHA-256 hash (hex) of the raw token. The raw token value is never persisted; lookups hash the
     * presented value and match against this column.
     */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The instant this token was consumed, or {@code null} if it is still outstanding. Set on
     * redemption, and on reissue to invalidate a superseded token. Either way the token is dead.
     */
    @Column(name = "used_at")
    private Instant usedAt;

    /** Managed by the database (DEFAULT CURRENT_TIMESTAMP). */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetToken() {
        // Required by JPA.
    }

    public PasswordResetToken(Integer userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Whether this token can still be redeemed as of {@code now}: unused and unexpired. */
    public boolean isRedeemable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    /** Marks this token as consumed. Idempotent: if it was already used, the original time stands. */
    public void markUsed() {
        if (this.usedAt == null) {
            this.usedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
