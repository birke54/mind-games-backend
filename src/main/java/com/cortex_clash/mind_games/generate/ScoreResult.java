package com.cortex_clash.mind_games.generate;

/**
 * The result of grading a board: the difficulty score produced by {@link BoardGrader}, i.e. the sum
 * of the weights of the solving techniques needed to logically solve the puzzle. Higher is harder.
 *
 * @param score the difficulty score (sum of applied technique weights)
 */
public record ScoreResult(int score) {}
