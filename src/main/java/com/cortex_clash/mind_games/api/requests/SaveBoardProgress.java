package com.cortex_clash.mind_games.api.requests;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Map;

/**
 * A save of the player's progress on a board: where the board stands, what they have pencilled in,
 * and how long they have been at it. The whole state is sent on every save — the server does not
 * apply deltas.
 *
 * @param currentState   the player's board, exactly 81 characters of digits {@code 0-9} row-major
 *                       ({@code '0'} is an empty cell)
 * @param notes          pencil marks keyed by row-major cell index ({@code "0"}..{@code "80"}),
 *                       each value the digits noted in that cell; omit cells with no notes.
 *                       May be null or empty, which clears every note.
 * @param elapsedSeconds total time spent on this board so far, in seconds
 */
public record SaveBoardProgress(
        @NotNull
        @Pattern(regexp = "[0-9]{81}", message = "currentState must be 81 digits, row-major")
        String currentState,

        Map<String, @NotNull List<Integer>> notes,

        @PositiveOrZero
        long elapsedSeconds) {
}
