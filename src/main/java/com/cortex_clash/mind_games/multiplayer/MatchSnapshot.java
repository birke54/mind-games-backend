package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.domain.Match;

import java.time.Instant;
import java.util.List;

/**
 * A match as one participant is entitled to see it: the match itself plus both players' progress,
 * with usernames resolved for display.
 *
 * <p>Progress is a correct-cell count and nothing more. The opponent's grid never appears here,
 * because a mostly-correct grid is a mostly-complete solution.
 *
 * @param match     the match row
 * @param players   both seats, host first
 * @param viewerBoardId the requesting player's board in this match, or {@code null} before the
 *                      race starts and no boards have been dealt
 * @param viewerSolution the solved grid, or {@code null} while the match is still live. This is
 *                       the one place a match's solution is ever released: the board endpoints
 *                       withhold it for as long as the board is in progress, which would otherwise
 *                       leave the player who <em>lost</em> never able to see the answer to a puzzle
 *                       they can no longer play
 */
public record MatchSnapshot(Match match, List<PlayerProgress> players, Long viewerBoardId,
                            String viewerSolution) {

    /**
     * One player's standing in the race.
     *
     * @param userId       the player
     * @param username     their display name
     * @param correctCells cells they currently have right, 0 to 81
     * @param finishedAt   when they completed their board, or {@code null}
     */
    public record PlayerProgress(Integer userId, String username, int correctCells, Instant finishedAt) {
    }
}
