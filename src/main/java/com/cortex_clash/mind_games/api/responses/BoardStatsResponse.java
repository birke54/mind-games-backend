package com.cortex_clash.mind_games.api.responses;

import java.util.Map;

/**
 * A player's lifetime board stats: the totals across every tier, plus the same figures broken out
 * per difficulty, keyed by the difficulty's lowercase name.
 *
 * @param boardsSolved      completed boards across all tiers
 * @param bestCompletedTime fastest completion across all tiers in seconds, or {@code null} if the
 *                          player has never finished a board
 * @param totalPlayTime     seconds spent across every board, finished or not
 * @param statsByDifficulty the same figures per tier; every tier is present, and one the player
 *                          has never been dealt reports zeroes and no best time
 */
public record BoardStatsResponse(
        long boardsSolved,
        Long bestCompletedTime,
        long totalPlayTime,
        Map<String, DifficultyBreakdown> statsByDifficulty) {

    /**
     * One tier's totals.
     *
     * @param boardsSolved     completed boards in this tier
     * @param boardsInProgress boards still unfinished in this tier
     * @param bestTime         fastest completion in seconds, or {@code null} if none is finished
     * @param solvedPlayTime   seconds spent on this tier's solved boards; over
     *                         {@code boardsSolved} this is a mean solve time, which is why it is
     *                         reported apart from {@code totalPlayTime}
     * @param totalPlayTime    seconds spent on this tier's boards, finished or not
     */
    public record DifficultyBreakdown(
            long boardsSolved,
            long boardsInProgress,
            Long bestTime,
            long solvedPlayTime,
            long totalPlayTime) {
    }
}
