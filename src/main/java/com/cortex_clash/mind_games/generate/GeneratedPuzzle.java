package com.cortex_clash.mind_games.generate;

/**
 * The output of a single generation: the {@code puzzle} to present to the player and its unique
 * completed {@code solution}. Both are 81-char row-major board strings (see {@link Board}).
 *
 * @param puzzle   the puzzle to present, an 81-char row-major string with {@code '0'} for empty cells
 * @param solution the puzzle's unique completed solution, an 81-char row-major string of digits
 */
public record GeneratedPuzzle(String puzzle, String solution) {}
