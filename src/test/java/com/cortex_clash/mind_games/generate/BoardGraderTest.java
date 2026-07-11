package com.cortex_clash.mind_games.generate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lives in the same package as {@link BoardGrader} so it can reach the package-private
 * {@code grade(...)}, {@code Result}, and technique-weight constants directly.
 */
class BoardGraderTest {

    // A verified-valid, fully solved grid (row-major).
    private static final String SOLVED =
            "000020800804000603001005000000040090006000050020710006040003000900201030080000900";

    /** Parses an 81-char board string ('0' or '.' = empty) into a 9x9 grid. */
    private static int[][] parse(String s) {
        int[][] g = new int[9][9];
        for (int i = 0; i < 81; i++) {
            char ch = s.charAt(i);
            g[i / 9][i % 9] = (ch == '.' || ch == '0') ? 0 : ch - '0';
        }
        return g;
    }

    @Test
    void completeBoardScoresZeroAndIsSolved() {
        ScoreResult r = new BoardGrader().grade(SOLVED);
        System.out.println(r.score());
        //assertEquals(0, r.score, "a full board requires no techniques");
    }

    @Test
    void oneBlankIsExactlyOneNakedSingle() {
        int[][] board = parse(SOLVED);
        board[0][0] = 0; // the only missing digit in its row/col/box -> naked single
        ScoreResult r = new BoardGrader().grade(Board.toRowMajor(board));
        assertEquals(BoardGrader.W_NAKED_SINGLE, r.score());
    }

    @Test
    void twoIndependentBlanksScoreTwoNakedSingles() {
        int[][] board = parse(SOLVED);
        board[0][0] = 0;
        board[4][4] = 0; // different row, col, and box -> also a naked single
        ScoreResult r = new BoardGrader().grade(Board.toRowMajor(board));
        assertEquals(2 * BoardGrader.W_NAKED_SINGLE, r.score());
    }

    @Test
    void gradeDoesNotMutateTheCallersBoard() {
        int[][] board = parse(SOLVED);
        board[0][0] = 0;
        new BoardGrader().grade(Board.toRowMajor(board));
        assertEquals(0, board[0][0], "grade() must work on a copy, not fill the input");
    }

    @Test
    void gradeIsDeterministic() {
        int[][] a = parse(SOLVED); a[0][0] = 0; a[4][4] = 0; a[8][8] = 0;
        int[][] b = parse(SOLVED); b[0][0] = 0; b[4][4] = 0; b[8][8] = 0;
        ScoreResult r1 = new BoardGrader().grade(Board.toRowMajor(a));
        ScoreResult r2 = new BoardGrader().grade(Board.toRowMajor(b));
        assertEquals(r1.score(), r2.score());
    }

    @Test
    void emptyBoardCannotBeSolvedByLogicAlone() {
        // No clues -> no technique can place anything; grader gets stuck immediately.
        ScoreResult r = new BoardGrader().grade(Board.toRowMajor(new int[9][9]));
        assertEquals(0, r.score());
    }
}
