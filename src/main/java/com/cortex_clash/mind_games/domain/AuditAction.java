package com.cortex_clash.mind_games.domain;

/** Maps to the {@code action} ENUM column on the {@code user_audit} table. */
public enum AuditAction {

    USER_REGISTERED("user_registered"),
    FAILED_REGISTRATION("failed_registration"),
    SUCCESSFUL_LOGIN("successful_login"),
    LOGOUT("logout"),
    FAILED_LOGIN("failed_login"),
    LOCKOUT("lockout"),
    PASSWORD_RESET_REQUESTED("password_reset_requested"),
    PASSWORD_RESET_COMPLETED("password_reset_completed");

    private final String dbValue;

    AuditAction(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * Returns the database string representation of this audit action.
     *
     * @return the lowercase DB value (e.g. {@code "successful_login"})
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolves the enum constant for a database string value.
     *
     * @param value the DB string value (e.g. {@code "failed_login"})
     * @return the matching {@link AuditAction}
     * @throws IllegalArgumentException if {@code value} does not match any known audit action
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
