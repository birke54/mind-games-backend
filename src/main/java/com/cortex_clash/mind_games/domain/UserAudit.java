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
 * An append-only audit-log entry recording an authentication-related action (login, logout,
 * failed login, lockout, registration, ...) for a given username. Entries are keyed by username
 * rather than user id so they survive even when the action concerns an unknown or deleted account.
 */
@Entity
@Table(name = "user_audit")
public class UserAudit {

    /**
     * The unique identifier for this audit entry.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The username the audited action concerns (may reference an account that does not exist).
     */
    @Column(name = "username", nullable = false, length = 25)
    private String username;

    /**
     * The action being recorded.
     */
    @Convert(converter = AuditActionConverter.class)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    /**
     * The instant the action occurred. Managed by the database (DEFAULT CURRENT_TIMESTAMP).
     */
    @Column(name = "timestamp", insertable = false, updatable = false)
    private Instant timestamp;

    /**
     * Protected no-argument constructor required by JPA.
     */
    protected UserAudit() {
        // Required by JPA.
    }

    /**
     * Constructs a new audit entry for the given username and action. The timestamp is assigned by
     * the database on insert.
     *
     * @param username the username the action concerns
     * @param action   the action being recorded
     */
    public UserAudit(String username, AuditAction action) {
        this.username = username;
        this.action = action;
    }

    /**
     * Returns the unique identifier of this audit entry.
     *
     * @return the entry id
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the username the action concerns.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the recorded action.
     *
     * @return the action
     */
    public AuditAction getAction() {
        return action;
    }

    /**
     * Returns the instant the action occurred.
     *
     * @return the timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
}
