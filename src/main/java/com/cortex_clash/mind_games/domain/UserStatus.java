package com.cortex_clash.mind_games.domain;

/**
 * The lifecycle status of a user account. Maps to the {@code status} ENUM column on the
 * {@code users} table; each constant carries its lowercase database string.
 */
public enum UserStatus {

    /** The account is active and may authenticate. */
    ACTIVE("active"),

    /** The account is locked after too many failed login attempts and may not authenticate. */
    LOCKED_OUT("locked_out"),

    /** The account is awaiting email/registration confirmation. */
    PENDING_CONFIRM("pending_confirm");

    private final String dbValue;

    UserStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * Returns the database string representation of this status.
     *
     * @return the lowercase DB value (e.g. {@code "active"})
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolves the enum constant for a database string value.
     *
     * @param value the DB string value (e.g. {@code "locked_out"})
     * @return the matching {@link UserStatus}
     * @throws IllegalArgumentException if {@code value} does not match any known status
     */
    public static UserStatus fromDbValue(String value) {
        for (UserStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown user status: " + value);
    }
}
