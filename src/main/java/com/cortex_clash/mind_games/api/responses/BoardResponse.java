package com.cortex_clash.mind_games.api.responses;

import com.cortex_clash.mind_games.domain.UserBoard;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A board belonging to the player: what they were dealt, where they are now, and the completed
 * state. The three board strings are 81 characters, row-major (index {@code = row * 9 + col}),
 * with {@code '0'} marking an empty cell.
 *
 * <p>{@code puzzle} is the immutable deal, so the frontend can tell a given (a clue that may not
 * be changed) from a digit the player entered: cell {@code i} is a given iff
 * {@code puzzle.charAt(i) != '0'}.
 *
 * <p>{@code status} is how the frontend knows to freeze the board. It is {@code "in_progress"}
 * until the saved grid first matches the solution, at which point the save that completed the
 * board returns {@code "completed"} with a {@code completedAt} stamp. That is terminal: the row is
 * now immutable, and any further save against it is rejected with 409. So on seeing
 * {@code "completed"} the UI should stop accepting input and stop sending saves — a board also
 * comes back {@code "completed"} from the resume list, which is what freezes it on reload.
 *
 * @param id             the board's id, used to save progress against it
 * @param difficulty     {@code "easy"}, {@code "moderate"}, or {@code "hard"}
 * @param status         {@code "in_progress"} or {@code "completed"}; server-derived, never
 *                       accepted from the client
 * @param puzzle         the starting state as dealt
 * @param currentState   the player's board right now; equal to {@code puzzle} on a fresh board
 * @param solution       the completed state
 * @param notes          pencil marks keyed by row-major cell index; cells with no notes are absent
 * @param elapsedSeconds total time the player has spent on this board
 * @param claimedAt      when the board was dealt
 * @param completedAt    when the board was completed, or {@code null} while in progress
 * @param lastModifiedAt when the board was last saved
 */
public record BoardResponse(
        Long id,
        String difficulty,
        String status,
        String puzzle,
        String currentState,
        String solution,
        Map<String, List<Integer>> notes,
        long elapsedSeconds,
        Instant claimedAt,
        Instant completedAt,
        Instant lastModifiedAt) {

    /** Projects a persisted board onto the wire format. */
    public static BoardResponse from(UserBoard board) {
        return new BoardResponse(
                board.getId(),
                board.getDifficulty().dbValue(),
                board.getStatus().dbValue(),
                board.getPuzzleState(),
                board.getCurrentState(),
                board.getSolutionState(),
                board.getNotes(),
                board.getElapsedSeconds(),
                board.getClaimedAt(),
                board.getCompletedAt(),
                board.getLastModifiedAt());
    }
}
