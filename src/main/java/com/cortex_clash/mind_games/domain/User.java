package com.cortex_clash.mind_games.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A registered user account. Stores the credentials (as a BCrypt hash), the failed-login counter
 * and lockout status used by the authentication flow, and database-managed audit timestamps.
 */
@Entity
@Table(name = "users")
public class User {

    /** Maximum username length; mirrors the {@code users.username} column and drives validation. */
    public static final int USERNAME_MAX_LENGTH = 25;

    /** Maximum email length; mirrors the {@code users.email} column and drives validation. */
    public static final int EMAIL_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "username", nullable = false, length = USERNAME_MAX_LENGTH, unique = true)
    private String username;

    @Column(name = "email", nullable = false, length = EMAIL_MAX_LENGTH, unique = true)
    private String email;

    /** BCrypt hash of the user's password. The plaintext password is never stored. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * The number of consecutive failed login attempts; reset to zero on a successful login and used
     * to trigger lockout once it reaches the threshold.
     */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Convert(converter = UserStatusConverter.class)
    @Column(name = "status", nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /** Managed by the database (DEFAULT CURRENT_TIMESTAMP). */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Managed by the database (ON UPDATE CURRENT_TIMESTAMP). */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected User() {
        // Required by JPA.
    }

    public User(String username, String email, String passwordHash) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public boolean isLockedOut() {
        return this.status == UserStatus.LOCKED_OUT;
    }

    public Integer getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        this.status = status;
    }

    public void setFailedAttempts(int failedAttempts) {
        if (failedAttempts < 0) {
            throw new IllegalArgumentException("failedAttempts cannot be negative");
        }
        this.failedAttempts = failedAttempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
