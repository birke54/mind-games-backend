package com.cortex_clash.mind_games.generate;

/**
 * Row-major string representation of a 9x9 Sudoku board: exactly 81 characters, read
 * left-to-right, top-to-bottom (index {@code = row * 9 + col}). A digit {@code '1'..'9'}
 * is a filled cell; {@code '0'} or {@code '.'} is empty. This is the canonical format at
 * the public boundary of {@link GenerateBoard} and {@link BoardGrader}; the solving and
 * grading algorithms convert to an {@code int[][]} for their internal work.
 */
final class Board {

    /** Utility class; not instantiable. */
    private Board() {}

    /**
     * Parses an 81-char row-major board string into a 9x9 grid. Both {@code '0'} and {@code '.'}
     * are treated as empty cells (value {@code 0}); digits {@code '1'..'9'} map to their int value.
     *
     * @param board the 81-character row-major board string
     * @return a {@code int[9][9]} grid where empty cells are {@code 0}
     * @throws IllegalArgumentException if {@code board} is null or not exactly 81 characters
     */
    static int[][] parse(String board) {
        if (board == null || board.length() != 81) {
            throw new IllegalArgumentException("board must be an 81-char row-major string");
        }
        int[][] grid = new int[9][9];
        for (int i = 0; i < 81; i++) {
            char ch = board.charAt(i);
            grid[i / 9][i % 9] = (ch == '.' || ch == '0') ? 0 : ch - '0';
        }
        return grid;
    }

    /**
     * Serializes a 9x9 grid to an 81-char row-major string, reading left-to-right, top-to-bottom.
     * Cell value {@code 0} is emitted as the character {@code '0'} (empty).
     *
     * @param grid a 9x9 grid of cell values ({@code 0} for empty, {@code 1..9} for filled)
     * @return the 81-character row-major representation
     */
    static String toRowMajor(int[][] grid) {
        StringBuilder sb = new StringBuilder(81);
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                sb.append((char) ('0' + grid[r][c]));
            }
        }
        return sb.toString();
    }
}
