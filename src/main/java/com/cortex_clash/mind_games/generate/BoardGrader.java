package com.cortex_clash.mind_games.generate;

/**
 * Estimates the difficulty of a Sudoku puzzle by logically solving it with a tiered set of
 * human-style techniques, adding a weighted cost each time a technique is successfully applied.
 * The total is returned as a {@link ScoreResult}: harder puzzles need more — and more expensive —
 * techniques, so they score higher.
 *
 * <p>Grading is greedy from cheapest technique to most expensive (see the {@code W_*} weights): on
 * each pass the first technique that makes progress is applied, its weight added, and the scan
 * restarts. When nothing applies, grading stops.
 *
 * <p>Candidates for each cell are held as a 9-bit mask in {@link #candidates}: bit {@code d-1} set
 * means digit {@code d} is still possible there, which lets the elimination techniques run as fast
 * bitwise operations.
 *
 * <p>Not thread-safe: {@link #grid} and {@link #candidates} are mutated during grading, so a single
 * instance must not be shared across concurrent {@code grade} calls.
 */
public class BoardGrader {
    // ---- Technique weights (cost per successful application) ----
    // Cheap techniques barely move the score; expensive ones are worth more.
    static final int W_NAKED_SINGLE = 1;
    static final int W_HIDDEN_SINGLE = 2;
    static final int W_LOCKED_CAND = 5;
    static final int W_NAKED_PAIR = 8;
    static final int W_HIDDEN_PAIR = 12;
    static final int W_NAKED_TRIPLE = 15;
    static final int W_XWING = 35;

    /**
     * Per-cell candidate bitmasks: bit {@code d-1} set means digit {@code d} is still possible at
     * that cell. A filled (or fully resolved) cell has mask {@code 0}.
     */
    int[][] candidates = new int[9][9];

    /** Working copy of the board being graded (a clone — the caller's board is never modified). Empty cells are {@code 0}. */
    int[][] grid;

    /**
     * Grades a board given as an 81-char row-major string ('0' or '.' = empty).
     *
     * @param board the 81-char row-major board string
     * @return the difficulty score
     */
    protected ScoreResult grade(String board) {
        return grade(Board.parse(board));
    }

    /**
     * Grades a board grid by logically solving it and summing the weights of the techniques used.
     * Operates on a private copy, so the caller's {@code board} is not modified.
     *
     * @param board a 9x9 grid, {@code 0} for empty cells
     * @return the difficulty score (sum of applied technique weights)
     */
    protected ScoreResult grade(int[][] board) {
        // work on a copy so we don't modify the original
        grid = new int[9][9];
        for (int r = 0; r < 9; r++) {
            grid[r] = board[r].clone();
        }

        initCandidates();

        int currentScore = 0;
        boolean solved = false;

        while (true) {
            if (nakedSingles())   { currentScore += W_NAKED_SINGLE;  continue; }
            if (hiddenSingles())  { currentScore += W_HIDDEN_SINGLE; continue; }
            if (lockedCandidates()){ currentScore += W_LOCKED_CAND;  continue; }
            if (nakedSubset(2))   { currentScore += W_NAKED_PAIR;    continue; }
            if (hiddenSubset(2))  { currentScore += W_HIDDEN_PAIR;   continue; }
            if (nakedSubset(3))   { currentScore += W_NAKED_TRIPLE;  continue; }
            if (xWing())          { currentScore += W_XWING;         continue; }
            break;   // nothing applied → stuck
        }

        return new ScoreResult(currentScore);
    }

    // ========================== Candidate bookkeeping ==========================

    /** Initializes the candidate mask of every empty cell from the current {@link #grid}. */
    void initCandidates() {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                candidates[r][c] = (grid[r][c] == 0 ? computeCandidates(r,c) : 0);
            }
        }
    }

    /**
     * Computes the candidate bitmask for cell ({@code r}, {@code c}): the digits not already used
     * in its row, column, or 3x3 box.
     *
     * @return a 9-bit mask of still-possible digits
     */
    int computeCandidates(int r, int c) {
        int used = 0;
        for (int x = 0; x < 9; x++) {
            if (grid[r][x] != 0) used |= 1 << (grid[r][x] - 1);
            if (grid[x][c] != 0) used |= 1 << (grid[x][c] - 1);
        }
        int br = 3 * (r / 3), bc = 3 * (c / 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (grid[br + i][bc + j] != 0) used |= 1 << (grid[br + i][bc + j] - 1);
        return (~used) & 0x1FF;
    }

    /** Places digit {@code d} at ({@code r}, {@code c}) and clears {@code d} from every peer's candidates (row, column, box). */
    void place(int r, int c, int d) {
        grid[r][c] = d;
        candidates[r][c] = 0;
        int bit = 1 << (d - 1);
        for (int x = 0; x < 9; x++) {
            candidates[r][x] &= ~bit;
            candidates[x][c] &= ~bit;
        }
        int br = 3 * (r / 3), bc = 3 * (c / 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                candidates[br + i][bc + j] &= ~bit;
    }

    /** Number of set bits in a candidate mask — i.e. how many digits are still possible. */
    static int bitCount(int m) { return Integer.bitCount(m); }

    // ================= Tier 1 =================

    /** Naked single: an empty cell with exactly one candidate — place that digit. @return {@code true} if one was placed. */
    boolean nakedSingles() {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (grid[r][c] == 0 && bitCount(candidates[r][c]) == 1) {
                    place(r, c, Integer.numberOfTrailingZeros(candidates[r][c]) + 1);
                    return true;
                }
        return false;
    }

    /** Hidden single: within a unit (row/column/box) a digit that fits in exactly one cell — place it there. */
    boolean hiddenSingles() {
        // For each unit, if a digit fits in exactly one cell, place it.
        for (int d = 1; d <= 9; d++) {
            int bit = 1 << (d - 1);
            // rows
            for (int r = 0; r < 9; r++) {
                int col = -1, cnt = 0;
                for (int c = 0; c < 9; c++)
                    if ((candidates[r][c] & bit) != 0) { cnt++; col = c; }
                if (cnt == 1) { place(r, col, d); return true; }
            }
            // cols
            for (int c = 0; c < 9; c++) {
                int row = -1, cnt = 0;
                for (int r = 0; r < 9; r++)
                    if ((candidates[r][c] & bit) != 0) { cnt++; row = r; }
                if (cnt == 1) { place(row, c, d); return true; }
            }
            // boxes
            for (int b = 0; b < 9; b++) {
                int br = 3 * (b / 3), bc = 3 * (b % 3);
                int fr = -1, fc = -1, cnt = 0;
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++)
                        if ((candidates[br + i][bc + j] & bit) != 0) {
                            cnt++; fr = br + i; fc = bc + j;
                        }
                if (cnt == 1) { place(fr, fc, d); return true; }
            }
        }
        return false;
    }

    // ================= Tier 2 =================

    /**
     * Locked candidates (pointing/claiming): if within a box a digit is confined to a single row or
     * column, eliminate that digit from the rest of that row/column outside the box.
     *
     * @return {@code true} if any candidate was eliminated
     */
    boolean lockedCandidates() {
        boolean changed = false;
        for (int b = 0; b < 9; b++) {
            int br = 3 * (b / 3), bc = 3 * (b % 3);
            for (int d = 1; d <= 9; d++) {
                int bit = 1 << (d - 1);
                int rows = 0, cols = 0, cnt = 0;
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++)
                        if ((candidates[br + i][bc + j] & bit) != 0) {
                            rows |= 1 << i; cols |= 1 << j; cnt++;
                        }
                if (cnt < 2) continue;
                // confined to a single row within the box
                if (bitCount(rows) == 1) {
                    int r = br + Integer.numberOfTrailingZeros(rows);
                    for (int c = 0; c < 9; c++)
                        if (c < bc || c >= bc + 3)
                            if ((candidates[r][c] & bit) != 0) {
                                candidates[r][c] &= ~bit; changed = true;
                            }
                }
                // confined to a single column within the box
                if (bitCount(cols) == 1) {
                    int c = bc + Integer.numberOfTrailingZeros(cols);
                    for (int r = 0; r < 9; r++)
                        if (r < br || r >= br + 3)
                            if ((candidates[r][c] & bit) != 0) {
                                candidates[r][c] &= ~bit; changed = true;
                            }
                }
            }
        }
        return changed;
    }

    /**
     * Naked subset of the given size (2 = pair, 3 = triple): if {@code size} cells in a unit share a
     * candidate union of exactly {@code size} digits, those digits are locked out of the rest of the
     * unit.
     *
     * @param size the subset size (2 or 3)
     * @return {@code true} if any candidate was eliminated
     */
    boolean nakedSubset(int size) {
        for (int[] unit : allUnits()) {
            // gather empty cells and their masks
            for (int a = 0; a < 9; a++) {
                if (candidates[unit[2*a]][unit[2*a+1]] == 0) continue;
                // build combinations greedily by union — simple O(n^2/n^3) scan
            }
            if (nakedSubsetInUnit(unit, size)) return true;
        }
        return false;
    }

    /** Applies {@link #nakedSubset(int)} within a single unit's empty cells. */
    boolean nakedSubsetInUnit(int[] unit, int size) {
        int n = 9;
        // indices of empty cells in this unit
        int[] idx = new int[9]; int m = 0;
        for (int a = 0; a < n; a++)
            if (candidates[unit[2*a]][unit[2*a+1]] != 0) idx[m++] = a;

        // choose `size` cells whose candidate union has exactly `size` bits
        int[] comb = new int[size];
        return combine(idx, m, size, 0, 0, comb, unit);
    }

    /**
     * Recursively enumerates every size-{@code size} combination of the unit's empty cells; when a
     * combination's candidate union has exactly {@code size} bits (a naked subset), eliminates those
     * digits from the unit's other cells.
     *
     * @return {@code true} as soon as a combination eliminates at least one candidate
     */
    boolean combine(int[] idx, int m, int size, int start, int depth,
                    int[] comb, int[] unit) {
        if (depth == size) {
            int union = 0;
            for (int k = 0; k < size; k++) {
                int a = idx[comb[k]];
                union |= candidates[unit[2*a]][unit[2*a+1]];
            }
            if (bitCount(union) != size) return false;
            // eliminate union bits from other cells in the unit
            boolean changed = false;
            boolean[] inComb = new boolean[m];
            for (int k = 0; k < size; k++) inComb[comb[k]] = true;
            for (int p = 0; p < m; p++) {
                if (inComb[p]) continue;
                int a = idx[p];
                int r = unit[2*a], c = unit[2*a+1];
                if ((candidates[r][c] & union) != 0) {
                    candidates[r][c] &= ~union; changed = true;
                }
            }
            return changed;
        }
        for (int p = start; p < m; p++) {
            comb[depth] = p;
            if (combine(idx, m, size, p + 1, depth + 1, comb, unit)) return true;
        }
        return false;
    }

    /**
     * Hidden subset (size 2): if {@code size} digits appear in only the same {@code size} cells of a
     * unit, strip all other candidates from those cells.
     *
     * @param size the subset size
     * @return {@code true} if any candidate was eliminated
     */
    boolean hiddenSubset(int size) {
        for (int[] unit : allUnits()) {
            for (int d1 = 1; d1 <= 9; d1++)
                for (int d2 = d1 + 1; d2 <= 9; d2++) {
                    int bits = (1 << (d1-1)) | (1 << (d2-1));
                    int cellsWith = 0, cnt = 0;
                    for (int a = 0; a < 9; a++) {
                        int mask = candidates[unit[2*a]][unit[2*a+1]];
                        if ((mask & bits) != 0) { cellsWith |= 1 << a; cnt++; }
                    }
                    if (cnt != size) continue;
                    // both digits must actually be confined to these cells
                    boolean valid = true;
                    for (int dd : new int[]{d1, d2}) {
                        int bit = 1 << (dd - 1), c2 = 0;
                        for (int a = 0; a < 9; a++)
                            if ((candidates[unit[2*a]][unit[2*a+1]] & bit) != 0) c2++;
                        if (c2 != size) { valid = false; break; }
                    }
                    if (!valid) continue;
                    boolean changed = false;
                    for (int a = 0; a < 9; a++) {
                        if ((cellsWith & (1 << a)) == 0) continue;
                        int r = unit[2*a], c = unit[2*a+1];
                        if ((candidates[r][c] & ~bits) != 0) {
                            candidates[r][c] &= bits; changed = true;
                        }
                    }
                    if (changed) return true;
                }
        }
        return false;
    }

    // ================= Tier 3 =================

    /** X-Wing (rows and columns) for each digit. @return {@code true} if any candidate was eliminated. */
    boolean xWing() {
        for (int d = 1; d <= 9; d++) {
            int bit = 1 << (d - 1);
            if (xWingRows(bit)) return true;
            if (xWingCols(bit)) return true;
        }
        return false;
    }

    /**
     * Row-based X-Wing for the digit represented by {@code bit}: two rows in which the digit is
     * limited to the same two columns let it be eliminated from those columns in all other rows.
     */
    boolean xWingRows(int bit) {
        // find two rows where digit fits in exactly the same two columns
        int[] colsInRow = new int[9];
        for (int r = 0; r < 9; r++) {
            int mask = 0, cnt = 0;
            for (int c = 0; c < 9; c++)
                if ((candidates[r][c] & bit) != 0) { mask |= 1 << c; cnt++; }
            colsInRow[r] = (cnt == 2) ? mask : 0;
        }
        for (int r1 = 0; r1 < 9; r1++) {
            if (colsInRow[r1] == 0) continue;
            for (int r2 = r1 + 1; r2 < 9; r2++) {
                if (colsInRow[r2] != colsInRow[r1]) continue;
                int cols = colsInRow[r1];
                int c1 = Integer.numberOfTrailingZeros(cols);
                int c2 = 31 - Integer.numberOfLeadingZeros(cols);
                boolean changed = false;
                for (int r = 0; r < 9; r++) {
                    if (r == r1 || r == r2) continue;
                    if ((candidates[r][c1] & bit) != 0) { candidates[r][c1] &= ~bit; changed = true; }
                    if ((candidates[r][c2] & bit) != 0) { candidates[r][c2] &= ~bit; changed = true; }
                }
                if (changed) return true;
            }
        }
        return false;
    }

    /** Column-based X-Wing for the digit represented by {@code bit} (the transpose of {@link #xWingRows(int)}). */
    boolean xWingCols(int bit) {
        int[] rowsInCol = new int[9];
        for (int c = 0; c < 9; c++) {
            int mask = 0, cnt = 0;
            for (int r = 0; r < 9; r++)
                if ((candidates[r][c] & bit) != 0) { mask |= 1 << r; cnt++; }
            rowsInCol[c] = (cnt == 2) ? mask : 0;
        }
        for (int c1 = 0; c1 < 9; c1++) {
            if (rowsInCol[c1] == 0) continue;
            for (int c2 = c1 + 1; c2 < 9; c2++) {
                if (rowsInCol[c2] != rowsInCol[c1]) continue;
                int rows = rowsInCol[c1];
                int r1 = Integer.numberOfTrailingZeros(rows);
                int r2 = 31 - Integer.numberOfLeadingZeros(rows);
                boolean changed = false;
                for (int c = 0; c < 9; c++) {
                    if (c == c1 || c == c2) continue;
                    if ((candidates[r1][c] & bit) != 0) { candidates[r1][c] &= ~bit; changed = true; }
                    if ((candidates[r2][c] & bit) != 0) { candidates[r2][c] &= ~bit; changed = true; }
                }
                if (changed) return true;
            }
        }
        return false;
    }

    // ================= Units helper =================

    /** Lazily-built cache of the 27 units, populated on the first {@link #allUnits()} call. */
    java.util.List<int[]> unitCache;

    /** Returns all 27 units (9 rows, 9 columns, 9 boxes), each encoded as an {@code int[18]} of alternating (row, col) pairs. */
    java.util.List<int[]> allUnits() {
        if (unitCache != null) return unitCache;
        unitCache = new java.util.ArrayList<>();
        for (int r = 0; r < 9; r++) {
            int[] u = new int[18];
            for (int c = 0; c < 9; c++) { u[2*c] = r; u[2*c+1] = c; }
            unitCache.add(u);
        }
        for (int c = 0; c < 9; c++) {
            int[] u = new int[18];
            for (int r = 0; r < 9; r++) { u[2*r] = r; u[2*r+1] = c; }
            unitCache.add(u);
        }
        for (int b = 0; b < 9; b++) {
            int br = 3*(b/3), bc = 3*(b%3);
            int[] u = new int[18]; int k = 0;
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) { u[k++] = br+i; u[k++] = bc+j; }
            unitCache.add(u);
        }
        return unitCache;
    }
}
