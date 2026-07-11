package com.cortex_clash.mind_games.domain;

/** Maps to the {@code action} ENUM column on the {@code user_audit} table. */
public enum AuditAction {
    /**
     * Represents a successful user registration event.
     */
    USER_REGISTERED("user_registered"),
    /**
     * Represents a failed user registration attempt.
     */
    FAILED_REGISTRATION("failed_registration"),
    /**
     * Represents a successful user login event.
     */
    SUCCESSFUL_LOGIN("successful_login"),
    /**
     * Represents a user logout event.
     */
    LOGOUT("logout"),
    /**
     * Represents a failed user login attempt.
     */
    FAILED_LOGIN("failed_login"),
    /**
     * Represents a user account lockout event.
     */
    LOCKOUT("lockout");

    /**
     * The database representation of this audit action.
     */
    private final String dbValue;

    /**
     * Constructs an AuditAction with the specified database value.
     *
     * @param dbValue the database representation of this audit action
     */
    AuditAction(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * Returns the database representation of this audit action.
     *
     * @return the database value
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Converts a database value to the corresponding AuditAction enum constant.
     *
     * @param value the database value to convert
     * @return the matching AuditAction constant
     * @throws IllegalArgumentException if no matching audit action is found
     */
    public static AuditAction fromDbValue(String value) {
        for (AuditAction action : values()) {
            if (action.dbValue.equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown audit action: " + value);
    }
}
