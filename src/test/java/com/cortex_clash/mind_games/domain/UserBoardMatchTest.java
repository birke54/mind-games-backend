package com.cortex_clash.mind_games.domain;

import com.cortex_clash.mind_games.api.responses.BoardResponse;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that keep a race honest: a match board does not hand its solution to the client, and
 * progress is counted from the solution rather than taken on trust.
 */
class UserBoardMatchTest {

    private static final String SOLUTION = "123456789".repeat(9);
    private static final String PUZZLE = "0".repeat(81);

    @Test
    void soloBoardSendsItsSolution() {
        UserBoard solo = new UserBoard(7, BoardDifficulty.EASY, PUZZLE, SOLUTION);

        assertTrue(solo.revealsSolution());
        assertEquals(SOLUTION, BoardResponse.from(solo).solution());
        assertNull(BoardResponse.from(solo).matchId());
    }

    @Test
    void matchBoardWithholdsItsSolution() {
        UserBoard match = new UserBoard(7, 42L, BoardDifficulty.EASY, PUZZLE, SOLUTION);

        assertTrue(match.isMatchBoard());
        assertFalse(match.revealsSolution());
        assertNull(BoardResponse.from(match).solution(),
                "a live match board must not ship the solution the race is run on");
        assertEquals(42L, BoardResponse.from(match).matchId());
    }

    @Test
    void matchBoardRevealsSolutionOnceItsOwnPlayerHasFinished() {
        UserBoard match = new UserBoard(7, 42L, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        match.saveProgress(SOLUTION, Map.of(), 120);

        assertTrue(match.isCompleted());
        assertTrue(match.revealsSolution());
        assertNotNull(BoardResponse.from(match).solution());
    }

    @Test
    void correctCellsCountsOnlyFilledCellsThatMatchTheSolution() {
        UserBoard board = new UserBoard(7, 42L, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        assertEquals(0, board.countCorrectCells(), "an empty grid has nothing right");

        // One right, one wrong, the rest empty: a wrong digit is not progress.
        String partial = SOLUTION.charAt(0) + "9" + "0".repeat(79);
        board.saveProgress(partial, Map.of(), 10);
        assertEquals(1, board.countCorrectCells());

        board.saveProgress(SOLUTION, Map.of(), 20);
        assertEquals(81, board.countCorrectCells());
    }
}
