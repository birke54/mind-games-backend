package com.cortex_clash.mind_games.api.requests;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Asks the server to fill one cell with its solved digit.
 *
 * <p>Serving this from the API rather than the client is what lets a board be hinted without the
 * browser holding its solution — which is exactly the situation a match board is in, since it
 * withholds its solution for as long as the race is live. Solo play can hint locally and never
 * needs this.
 *
 * @param cellIndex the cell the player had selected, row-major 0–80, or {@code null} to let the
 *                  server take the first empty cell. A selection that is a given, or already
 *                  filled, is treated the same way: a hint should not be a no-op.
 */
public record RevealHint(
        @Min(value = 0, message = "cellIndex must be between 0 and 80")
        @Max(value = 80, message = "cellIndex must be between 0 and 80")
        Integer cellIndex) {
}
