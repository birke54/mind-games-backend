package com.cortex_clash.mind_games.domain;

/**
 * The lifecycle status of a two-player match. Maps to the {@code status} ENUM column on the
 * {@code matches} table; each constant carries its lowercase database string.
 */
public enum MatchStatus {

    /** Created by the host and advertising a join code. No board has been dealt yet. */
    WAITING("waiting"),

    /** Both players are in, both boards are dealt, and the race is running. */
    ACTIVE("active"),

    /** One player completed their board. Terminal. */
    FINISHED("finished"),

    /** Expired while waiting, or given up on. Terminal. */
    ABANDONED("abandoned");

    private final String dbValue;

    MatchStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * Returns the database string representation of this status.
     *
     * @return the lowercase DB value (e.g. {@code "waiting"})
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolves the enum constant for a database string value.
     *
     * @param value the DB string value (e.g. {@code "active"})
     * @return the matching {@link MatchStatus}
     * @throws IllegalArgumentException if {@code value} does not match any known status
     */
    public static MatchStatus fromDbValue(String value) {
        for (MatchStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown match status: " + value);
    }
}
