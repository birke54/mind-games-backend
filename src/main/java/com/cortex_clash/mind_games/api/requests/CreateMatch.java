package com.cortex_clash.mind_games.api.requests;

import jakarta.validation.constraints.NotBlank;

/**
 * Opens a two-player lobby at the given difficulty.
 *
 * @param difficulty {@code "easy"}, {@code "moderate"}, or {@code "hard"}
 */
public record CreateMatch(@NotBlank(message = "difficulty is required") String difficulty) {
}
