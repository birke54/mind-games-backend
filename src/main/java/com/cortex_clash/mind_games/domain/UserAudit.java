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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The username the audited action concerns (may reference an account that does not exist). */
    @Column(name = "username", nullable = false, length = 25)
    private String username;

    @Convert(converter = AuditActionConverter.class)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    /** Managed by the database (DEFAULT CURRENT_TIMESTAMP). */
    @Column(name = "timestamp", insertable = false, updatable = false)
    private Instant timestamp;

    protected UserAudit() {
        // Required by JPA.
    }

    public UserAudit(String username, AuditAction action) {
        this.username = username;
        this.action = action;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public AuditAction getAction() {
        return action;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
