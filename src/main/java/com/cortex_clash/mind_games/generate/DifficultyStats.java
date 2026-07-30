package com.cortex_clash.mind_games.generate;

/**
 * Per-difficulty play totals for one player, materialised straight from an aggregate so the stats
 * path never loads the boards themselves — a finished board carries three 81-char grids and its
 * pencil marks, none of which a counter needs.
 *
 * <p>Rows cover every tier the player has been dealt, finished or not, so a tier can report boards
 * played with nothing solved in it. A tier they have never played is an absent row rather than a
 * row of zeroes; {@link BoardService#retrieveStats} is what turns that gap into the zeroes the API
 * reports.
 */
public interface DifficultyStats {

    BoardDifficulty getDifficulty();

    long getBoardsSolved();

    long getBoardsInProgress();

    /**
     * Seconds spent on the tier's <em>solved</em> boards. Kept apart from {@link #getTotalPlayTime()}
     * because dividing it by {@link #getBoardsSolved()} is a mean solve time, which the stats page
     * shows per tier — folding in time on a board still being played would inflate it.
     */
    long getSolvedPlayTime();

    /** Seconds spent on the tier's boards, finished or not. */
    long getTotalPlayTime();

    /**
     * Fastest completion in the tier, in the client-reported seconds stored on the board, or
     * {@code null} if the player has not finished one here yet.
     */
    Long getBestTime();
}
