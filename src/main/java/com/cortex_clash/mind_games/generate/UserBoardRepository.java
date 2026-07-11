package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.domain.UserBoard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link UserBoard} — the boards users have claimed and are playing.
 */
@Repository
public interface UserBoardRepository extends JpaRepository<UserBoard, Long> {

    /**
     * Lists a user's boards, most recently played first.
     *
     * @param userId the owning user
     * @return that user's boards, newest activity first
     */
    List<UserBoard> findByUserIdOrderByLastModifiedAtDesc(Integer userId);

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
}
