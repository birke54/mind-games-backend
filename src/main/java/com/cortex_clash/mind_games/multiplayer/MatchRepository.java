package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.domain.Match;
import com.cortex_clash.mind_games.domain.MatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Match} — the two-player races.
 */
@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {

    /**
     * Finds a match by its join code.
     *
     * @param joinCode the code, already upper-cased
     * @return the match, or empty if no match ever used that code
     */
    Optional<Match> findByJoinCode(String joinCode);

    /**
     * Finds a match by join code, holding a write lock on the row until the caller's transaction
     * commits.
     *
     * <p>The lock is what makes a lobby seat-limited. Two players racing to join the same code
     * would otherwise both read it as {@code waiting}, both deal themselves a board, and both
     * believe they were the second player — leaving a three-player match and a board dealt out of
     * the pool for nobody.
     *
     * @param joinCode the code, already upper-cased
     * @return the match, or empty if no match ever used that code
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Match> findLockedByJoinCode(String joinCode);

    /**
     * Finds a match by id, holding a write lock on the row until the caller's transaction commits.
     *
     * <p>Used when a board completes: two players finishing within a few milliseconds of each other
     * must not both be recorded as the winner, so the read-decide-write of
     * {@link Match#finishWith(Integer)} runs under this lock.
     *
     * @param id the match id
     * @return the match, or empty if there is no such match
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Match> findLockedById(Long id);

    boolean existsByJoinCode(String joinCode);

    /**
     * Finds matches sitting in a given status since before a cutoff — lobbies nobody joined.
     *
     * @param status the status to sweep
     * @param cutoff matches created before this are stale
     * @return the stale matches
     */
    List<Match> findByStatusAndCreatedAtBefore(MatchStatus status, Instant cutoff);

    /**
     * Finds matches that started before a cutoff and are still running — races both players walked
     * away from.
     *
     * @param status the status to sweep
     * @param cutoff matches started before this are stale
     * @return the stale matches
     */
    List<Match> findByStatusAndStartedAtBefore(MatchStatus status, Instant cutoff);
}
