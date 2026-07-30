package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.domain.BoardStatus;
import com.cortex_clash.mind_games.domain.UserBoard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link UserBoard} — the boards users have claimed and are playing.
 */
@Repository
public interface UserBoardRepository extends JpaRepository<UserBoard, Long> {

    /**
     * Lists a user's five most recently played boards in one status, newest activity first. The
     * limit applies after the status filter, so a player with a long tail of finished puzzles still
     * gets five of the ones they can resume.
     *
     * @param status the status to list
     * @param userId the owning user
     * @return at most five of that user's boards in that status, newest activity first
     */
    List<UserBoard> findFirst5ByStatusAndUserIdOrderByLastModifiedAtDesc(BoardStatus status, Integer userId);

    /**
     * Looks up one of a user's boards. Scoping the lookup by {@code userId} is what stops one user
     * from reading or saving over another user's board by guessing an id.
     *
     * @param id     the board id
     * @param userId the user the board must belong to
     * @return the board, or empty if it does not exist or belongs to someone else
     */
    Optional<UserBoard> findByIdAndUserId(Long id, Integer userId);

    /**
     * Finds a player's board within a match. A match deals exactly one board per player, so
     * {@code (matchId, userId)} identifies it without needing the board id.
     *
     * @param matchId the match
     * @param userId  the player
     * @return that player's board in that match, or empty if none was dealt
     */
    Optional<UserBoard> findByMatchIdAndUserId(Long matchId, Integer userId);

    /**
     * The same lookup, but holding a write lock on the row until the caller's transaction commits.
     *
     * <p>Used by the save path, where completion is a terminal, one-way transition: without the
     * lock two concurrent saves — a double-clicked "done", or a retried request — could both read
     * the board as still in progress and both write, letting a save land on an already-completed
     * board and overwrite the state that was supposed to be frozen.
     *
     * @param id     the board id
     * @param userId the user the board must belong to
     * @return the board, or empty if it does not exist or belongs to someone else
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserBoard> findLockedByIdAndUserId(Long id, Integer userId);

    /**
     * Totals one user's boards per difficulty tier. Counting in the database keeps the stats path
     * off the boards themselves — each row carries three 81-char grids and a notes blob that no
     * counter needs.
     *
     * <p>The grouping is over every board the user owns, not just their completed ones, so a tier
     * they have started but never finished still returns a row — with a null {@code bestTime}.
     *
     * @param userId     the owning user
     * @param completed  the status counted as solved
     * @param inProgress the status counted as unfinished
     * @return one row per tier the user has played; tiers they have never been dealt are absent
     */
    @Query("""
            SELECT b.difficulty AS difficulty,
                   SUM(CASE WHEN b.status = :completed THEN 1 ELSE 0 END) AS boardsSolved,
                   SUM(CASE WHEN b.status = :inProgress THEN 1 ELSE 0 END) AS boardsInProgress,
                   SUM(CASE WHEN b.status = :completed THEN b.elapsedSeconds ELSE 0 END) AS solvedPlayTime,
                   SUM(b.elapsedSeconds) AS totalPlayTime,
                   MIN(CASE WHEN b.status = :completed THEN b.elapsedSeconds END) AS bestTime
            FROM UserBoard b
            WHERE b.userId = :userId
            GROUP BY b.difficulty
            """)
    List<DifficultyStats> aggregateByDifficulty(@Param("userId") Integer userId,
                                                @Param("completed") BoardStatus completed,
                                                @Param("inProgress") BoardStatus inProgress);
}
