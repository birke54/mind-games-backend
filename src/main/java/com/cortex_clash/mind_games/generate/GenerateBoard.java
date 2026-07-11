package com.cortex_clash.mind_games.generate;

import java.util.concurrent.ThreadLocalRandom;

public class GenerateBoard {
    private final BoardDifficulty boardDifficulty;
    private final BoardGrader boardGrader = new BoardGrader();
    private final int[][] grid = new int[9][9];

    // Absolute lower bound for easy difficulty band and upper score bound of each difficulty band.
    // A board scoring above hardThreshold is harder than any target tier and is treated as unclassified (skipped during generation).
    private final int minThreshold;
    private final int easyThreshold;
    private final int moderateThreshold;
    private final int hardThreshold;

    public GenerateBoard(BoardDifficulty targetDifficulty,
                         int minThreshold, int easyThreshold, int moderateThreshold, int hardThreshold) {
        this.minThreshold = minThreshold;
        this.boardDifficulty = targetDifficulty;
        this.easyThreshold = easyThreshold;
        this.moderateThreshold = moderateThreshold;
        this.hardThreshold = hardThreshold;
    }

    /** Classifies a graded score into a difficulty tier, or {@code null} if it exceeds all tiers. */
    private BoardDifficulty classify(int score) {
        if (score < minThreshold) return null;
        if (score <= easyThreshold) return BoardDifficulty.EASY;
        if (score <= moderateThreshold) return BoardDifficulty.MODERATE;
        if (score <= hardThreshold) return BoardDifficulty.HARD;
        return null;
    }

    public GeneratedPuzzle generate() {
        randomizeDiagonals();
        solve();
        // After solve() the grid is a complete, valid solution; capture it before prepareBoard()
        // digs holes. That solution is the puzzle's unique answer (prepareBoard keeps uniqueness).
        String solution = Board.toRowMajor(grid);
        prepareBoard();

        return new GeneratedPuzzle(Board.toRowMajor(grid), solution);
    }

    private void prepareBoard() {
        int[][] coords = {
                {0, 0}, {0, 1}, {0, 2}, {0, 3}, {0, 4}, {0, 5}, {0, 6}, {0, 7}, {0, 8},
                {1, 0}, {1, 1}, {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6}, {1, 7}, {1, 8},
                {2, 0}, {2, 1}, {2, 2}, {2, 3}, {2, 4}, {2, 5}, {2, 6}, {2, 7}, {2, 8},
                {3, 0}, {3, 1}, {3, 2}, {3, 3}, {3, 4}, {3, 5}, {3, 6}, {3, 7}, {3, 8},
                {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}, {4, 7}, {4, 8},
                {5, 0}, {5, 1}, {5, 2}, {5, 3}, {5, 4}, {5, 5}, {5, 6}, {5, 7}, {5, 8},
                {6, 0}, {6, 1}, {6, 2}, {6, 3}, {6, 4}, {6, 5}, {6, 6}, {6, 7}, {6, 8},
                {7, 0}, {7, 1}, {7, 2}, {7, 3}, {7, 4}, {7, 5}, {7, 6}, {7, 7}, {7, 8},
                {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 6}, {8, 7}, {8, 8},
        };
        shuffle(coords);
        for (int[] coord : coords) {
            int row = coord[0];
            int col = coord[1];
            int[] currentValuePair = {grid[row][col], grid[8 - row][8 - col]};
            grid[row][col] = 0;
            grid[8 - row][8 - col] = 0;
            if (countSolutions(grid, 2) != 1) {
                grid[row][col] = currentValuePair[0];
                grid[8 - row][8 - col] = currentValuePair[1];
                continue;
            }
            ScoreResult boardGrade = boardGrader.grade(grid);
            if (classify(boardGrade.score()) == boardDifficulty) {
                return;
            }
        }
        throw new RuntimeException("Generated board has no permutation that match the target difficulty");
    }

    // Counts solutions but bails out as soon as it finds 2.
    // Returns 0, 1, or 2 (2 means "2 or more").
    static int countSolutions(int[][] grid, int limit) {
        // find first empty cell
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (grid[r][c] == 0) {
                    int total = 0;
                    for (int n = 1; n <= 9; n++) {
                        if (isValid(grid, r, c, n)) {
                            grid[r][c] = n;
                            total += countSolutions(grid, limit);
                            grid[r][c] = 0;          // undo
                            if (total >= limit) return total;  // early exit
                        }
                    }
                    return total;   // no valid digit here → dead end (0)
                }
            }
        }
        return 1;   // no empty cells → this is one complete solution
    }

    static boolean isValid(int[][] grid, int r, int c, int n) {
        for (int x = 0; x < 9; x++) {
            if (grid[r][x] == n) return false;   // row
            if (grid[x][c] == n) return false;   // col
        }
        int br = 3 * (r / 3), bc = 3 * (c / 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (grid[br + i][bc + j] == n) return false;   // box
        return true;
    }

    /**
     * Fills every empty cell (value {@code 0}) via depth-first backtracking: find the next
     * empty cell, try each digit that doesn't violate row/column/box constraints, recurse, and
     * undo the choice if the recursion hits a dead end.
     *
     * @return {@code true} once the whole grid is legally filled, {@code false} if the current
     *         partial grid cannot be completed (triggers backtracking one level up).
     */
    private boolean solve() {
        int[] randomOrder = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        shuffle(randomOrder);
        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 9; col++) {
                if (grid[row][col] == 0) {
                    for (int index = 0; index < 9; index++) {
                        if (canPlace(row, col, randomOrder[index])) {
                            grid[row][col] = randomOrder[index];
                            if (solve()) {
                                return true;
                            }
                            grid[row][col] = 0; // dead end: undo and try the next value
                        }
                    }
                    return false; // no digit fits here -> backtrack
                }
            }
        }
        return true; // no empty cells remain -> solved
    }

    /** Whether {@code value} can legally go at ({@code row}, {@code col}) in the current grid. */
    private boolean canPlace(int row, int col, int value) {
        for (int i = 0; i < 9; i++) {
            if (grid[row][i] == value || grid[i][col] == value) {
                return false; // already in this row or column
            }
        }
        int boxRow = (row / 3) * 3;
        int boxCol = (col / 3) * 3;
        for (int i = boxRow; i < boxRow + 3; i++) {
            for (int j = boxCol; j < boxCol + 3; j++) {
                if (grid[i][j] == value) {
                    return false; // already in this 3x3 box
                }
            }
        }
        return true;
    }

    private void randomizeDiagonals() {
        int[] firstRow = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] secondRow = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] thirdRow = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        shuffle(firstRow);
        shuffle(secondRow);
        shuffle(thirdRow);
        int count = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                this.grid[i][j] = firstRow[count];
                this.grid[i + 3][j + 3] = secondRow[count];
                this.grid[i + 6][j + 6] = thirdRow[count];
                count++;
            }
        }
    }

    /** Fisher–Yates shuffle of a primitive {@code int} array (e.g. the digits 1-9). */
    private static void shuffle(int[] array) {
        for (int i = array.length - 1; i > 0; i--) {
            int k = ThreadLocalRandom.current().nextInt(i + 1);
            int temp = array[i];
            array[i] = array[k];
            array[k] = temp;
        }
    }

    /**
     * Fisher–Yates shuffle of an array of objects. Works for {@code int[][]} because that is an
     * array of {@code int[]} rows — only the row references are swapped, so no cells are copied.
     */
    private static <T> void shuffle(T[] array) {
        for (int i = array.length - 1; i > 0; i--) {
            int k = ThreadLocalRandom.current().nextInt(i + 1);
            T temp = array[i];
            array[i] = array[k];
            array[k] = temp;
        }
    }
}
