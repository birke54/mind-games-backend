package com.cortex_clash.mind_games.domain;

/**
 * The lifecycle status of a board a user has claimed. Maps to the {@code status} ENUM column on the
 * {@code user_boards} table; each constant carries its lowercase database string.
 */
public enum BoardStatus {

    /** The player has the board and has not finished it. Every board starts here. */
    IN_PROGRESS("in_progress"),

    /** The player's grid matches the solution. */
    COMPLETED("completed");

    private final String dbValue;

    BoardStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * Returns the database string representation of this status.
     *
     * @return the lowercase DB value (e.g. {@code "in_progress"})
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolves the enum constant for a database string value.
     *
     * @param value the DB string value (e.g. {@code "completed"})
     * @return the matching {@link BoardStatus}
     * @throws IllegalArgumentException if {@code value} does not match any known status
     */
    public static BoardStatus fromDbValue(String value) {
        for (BoardStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown board status: " + value);
    }
}
