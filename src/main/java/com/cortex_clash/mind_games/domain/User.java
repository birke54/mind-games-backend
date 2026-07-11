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

    /**
     * The unique identifier for this user.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /**
     * The user's unique username.
     */
    @Column(name = "username", nullable = false, length = USERNAME_MAX_LENGTH, unique = true)
    private String username;

    /**
     * The user's unique email address.
     */
    @Column(name = "email", nullable = false, length = EMAIL_MAX_LENGTH, unique = true)
    private String email;

    /**
     * The BCrypt hash of the user's password. The plaintext password is never stored.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * The number of consecutive failed login attempts; reset to zero on a successful login and used
     * to trigger lockout once it reaches the threshold.
     */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    /**
     * The account status (active, locked out, or pending confirmation). Defaults to active.
     */
    @Convert(converter = UserStatusConverter.class)
    @Column(name = "status", nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * The instant the account was created. Managed by the database (DEFAULT CURRENT_TIMESTAMP).
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * The instant the account was last updated. Managed by the database
     * (ON UPDATE CURRENT_TIMESTAMP).
     */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * Protected no-argument constructor required by JPA.
     */
    protected User() {
        // Required by JPA.
    }

    /**
     * Constructs a new user with the given credentials. Status defaults to active and the
     * failed-attempt counter to zero.
     *
     * @param username     the unique username
     * @param email        the unique email address
     * @param passwordHash the BCrypt hash of the user's password
     */
    public User(String username, String email, String passwordHash) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    /**
     * Whether this account is currently locked out.
     *
     * @return {@code true} if the status is {@link UserStatus#LOCKED_OUT}
     */
    public boolean isLockedOut() {
        return this.status == UserStatus.LOCKED_OUT;
    }

    /**
     * Returns the unique identifier of this user.
     *
     * @return the user id
     */
    public Integer getId() {
        return id;
    }

    /**
     * Returns the username.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the email address.
     *
     * @return the email address
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns the BCrypt password hash.
     *
     * @return the password hash
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Replaces the stored password hash (e.g. on a password change).
     *
     * @param passwordHash the new BCrypt password hash
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Returns the number of consecutive failed login attempts.
     *
     * @return the failed-attempt count
     */
    public int getFailedAttempts() {
        return failedAttempts;
    }

    /**
     * Returns the account status.
     *
     * @return the current {@link UserStatus}
     */
    public UserStatus getStatus() {
        return status;
    }

    /**
     * Sets the account status.
     *
     * @param status the new status
     * @throws IllegalArgumentException if {@code status} is null
     */
    public void setStatus(UserStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        this.status = status;
    }

    /**
     * Sets the consecutive failed-login counter.
     *
     * @param failedAttempts the new failed-attempt count
     * @throws IllegalArgumentException if {@code failedAttempts} is negative
     */
    public void setFailedAttempts(int failedAttempts) {
        if (failedAttempts < 0) {
            throw new IllegalArgumentException("failedAttempts cannot be negative");
        }
        this.failedAttempts = failedAttempts;
    }

    /**
     * Returns the instant the account was created.
     *
     * @return the creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the instant the account was last updated.
     *
     * @return the last-updated timestamp
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
