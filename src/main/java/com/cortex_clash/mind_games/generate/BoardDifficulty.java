package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Difficulty tiers for a generated board, ordered easiest to hardest. Maps to the
 * {@code difficulty_rating} ENUM column on the {@code generated_boards} table; each constant
 * carries its lowercase database string.
 */
public enum BoardDifficulty {

    /** Easiest tier: solvable with only basic techniques. */
    EASY("easy"),

    /** Middle tier: requires intermediate solving techniques. */
    MODERATE("moderate"),

    /** Hardest tier: requires advanced solving techniques. */
    HARD("hard");

    private final String dbValue;

    BoardDifficulty(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * Returns the database string representation of this difficulty.
     *
     * @return the lowercase DB value (e.g. {@code "moderate"})
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolves the enum constant for a database string value.
     *
     * @param value the DB string value (e.g. {@code "hard"})
     * @return the matching {@link BoardDifficulty}
     * @throws IllegalArgumentException if {@code value} does not match any known difficulty
     */
    public static BoardDifficulty fromDbValue(String value) {
        for (BoardDifficulty difficulty : values()) {
            if (difficulty.dbValue.equals(value)) {
                return difficulty;
            }
        }
        throw new IllegalArgumentException("Unknown board difficulty: " + value);
    }
}
