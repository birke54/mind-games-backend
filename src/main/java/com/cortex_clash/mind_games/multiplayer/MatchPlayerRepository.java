package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.domain.MatchPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link MatchPlayer} — the seats in a match.
 */
@Repository
public interface MatchPlayerRepository extends JpaRepository<MatchPlayer, Long> {

    /**
     * Lists the players in a match, in join order — host first.
     *
     * @param matchId the match
     * @return its players, earliest seat first
     */
    List<MatchPlayer> findByMatchIdOrderByJoinedAtAsc(Long matchId);

    /**
     * Looks up one player's seat.
     *
     * @param matchId the match
     * @param userId  the player
     * @return the seat, or empty if that player is not in that match
     */
    Optional<MatchPlayer> findByMatchIdAndUserId(Long matchId, Integer userId);

    /**
     * Counts the seats taken in a match. A match is full at two.
     *
     * @param matchId the match
     * @return the number of players seated
     */
    long countByMatchId(Long matchId);
}
