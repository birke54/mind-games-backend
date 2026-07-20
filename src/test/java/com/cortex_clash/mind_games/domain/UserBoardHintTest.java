package com.cortex_clash.mind_games.domain;

import com.cortex_clash.mind_games.generate.BoardDifficulty;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side hints: choosing which cell to fill, and filling it on the same terms as any save.
 */
class UserBoardHintTest {

    private static final String SOLUTION = "123456789".repeat(9);

    /** Cell 0 is a given; every other cell is empty. */
    private static final String PUZZLE = SOLUTION.charAt(0) + "0".repeat(80);

    private UserBoard board() {
        return new UserBoard(7, BoardDifficulty.EASY, PUZZLE, SOLUTION);
    }

    @Test
    void revealsTheSelectedCell() {
        UserBoard board = board();
        int target = board.chooseHintCell(40);

        assertEquals(40, target);
        board.revealCell(target);
        assertEquals(SOLUTION.charAt(40), board.getCurrentState().charAt(40));
    }

    @Test
    void fallsBackToTheFirstEmptyCellWhenTheSelectionIsAGiven() {
        // Cell 0 is a given, so a hint there would be a no-op the player has to puzzle over.
        assertEquals(1, board().chooseHintCell(0));
    }

    @Test
    void fallsBackWhenTheSelectedCellIsAlreadyFilled() {
        UserBoard board = board();
        board.saveProgress(PUZZLE.substring(0, 5) + "9" + PUZZLE.substring(6), Map.of(), 0);

        assertEquals(1, board.chooseHintCell(5));
    }

    @Test
    void ignoresAnOutOfRangeSelection() {
        assertEquals(1, board().chooseHintCell(999));
        assertEquals(1, board().chooseHintCell(-4));
        assertEquals(1, board().chooseHintCell(null));
    }

    @Test
    void reportsNothingToRevealOnAFullGrid() {
        UserBoard board = board();
        // Full but wrong — no empty cell to hint, and not completed either.
        board.saveProgress("9".repeat(81), Map.of(), 0);

        assertEquals(-1, board.chooseHintCell(null));
        assertTrue(!board.isCompleted());
    }

    @Test
    void aHintFillingTheLastCellCompletesTheBoardLikeAnyOtherSave() {
        UserBoard board = board();
        board.saveProgress(SOLUTION.substring(0, 80) + "0", Map.of(), 30);

        board.revealCell(board.chooseHintCell(null));

        assertTrue(board.isCompleted(), "a hint must not be a way around the completion rules");
        assertEquals(SOLUTION, board.getCurrentState());
    }

    @Test
    void revealingKeepsNotesAndTheClock() {
        UserBoard board = board();
        board.saveProgress(PUZZLE, Map.of("40", java.util.List.of(1, 2)), 250);

        board.revealCell(board.chooseHintCell(3));

        assertEquals(250, board.getElapsedSeconds());
        assertEquals(java.util.List.of(1, 2), board.getNotes().get("40"));
    }
}
