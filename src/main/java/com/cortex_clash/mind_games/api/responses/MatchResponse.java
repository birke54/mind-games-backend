package com.cortex_clash.mind_games.api.responses;

import com.cortex_clash.mind_games.domain.Match;
import com.cortex_clash.mind_games.multiplayer.MatchSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * A match as one of its players sees it. This is the shape the frontend polls while a race is on.
 *
 * <p>{@code status} drives the whole client flow: {@code "waiting"} shows the join code and keeps
 * polling, {@code "active"} means {@code boardId} is populated and play can start, and
 * {@code "finished"} names a {@code winnerUserId}.
 *
 * <p>Each player carries a {@code correctCells} count and nothing else — no grid, no notes. That is
 * the deliberate limit of what a player learns about their opponent: a mostly-correct grid is
 * a mostly-complete solution, and the puzzle's solution is withheld from match boards precisely so
 * neither player can shortcut the race.
 *
 * <p>{@code solution} is the one exception, and only once the match is no longer live. Withholding
 * it is about the race, so when the race is over the reason goes with it — otherwise the player who
 * lost is left with a board frozen in progress and no way to ever see the answer. Both players get
 * it, on the losing side too, which is why it hangs off the match rather than the board.
 *
 * @param id            the match id, used to poll it
 * @param joinCode      the code to share; still useful after the start for a rejoin
 * @param difficulty    {@code "easy"}, {@code "moderate"}, or {@code "hard"}
 * @param status        {@code "waiting"}, {@code "active"}, {@code "finished"}, or
 *                      {@code "abandoned"}
 * @param boardId       the requesting player's board in this match; {@code null} until the race
 *                      starts, since no board is dealt while the lobby waits
 * @param hostUserId    the player who opened the lobby
 * @param winnerUserId  the first player to complete their board, or {@code null}
 * @param players       both seats, host first
 * @param solution      the solved grid, or {@code null} while the match is still live — see the
 *                      note above on releasing it once the race is over
 * @param startedAt     when the second player joined; the clock the result is measured from
 * @param finishedAt    when the race was decided, or {@code null}
 */
public record MatchResponse(
        Long id,
        String joinCode,
        String difficulty,
        String status,
        Long boardId,
        Integer hostUserId,
        Integer winnerUserId,
        List<PlayerResponse> players,
        String solution,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * One player's standing in the race.
     *
     * @param userId       the player
     * @param username     their display name
     * @param correctCells cells they currently have right, 0 to 81 — the only progress signal
     *                     shared between opponents
     * @param finishedAt   when they completed their board, or {@code null}
     */
    public record PlayerResponse(Integer userId, String username, int correctCells, Instant finishedAt) {
    }

    /** Projects a match snapshot onto the wire format. */
    public static MatchResponse from(MatchSnapshot snapshot) {
        Match match = snapshot.match();
        List<PlayerResponse> players = snapshot.players().stream()
                .map(player -> new PlayerResponse(
                        player.userId(), player.username(), player.correctCells(), player.finishedAt()))
                .toList();

        return new MatchResponse(
                match.getId(),
                match.getJoinCode(),
                match.getDifficulty().dbValue(),
                match.getStatus().dbValue(),
                snapshot.viewerBoardId(),
                match.getHostUserId(),
                match.getWinnerUserId(),
                players,
                snapshot.viewerSolution(),
                match.getStartedAt(),
                match.getFinishedAt());
    }
}
