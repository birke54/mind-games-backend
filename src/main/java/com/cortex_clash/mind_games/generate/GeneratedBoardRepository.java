package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.domain.GeneratedBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data repository for {@link GeneratedBoard} — the pool of pregenerated puzzles. Provides
 * the standard CRUD operations, a per-difficulty count used by the pool-refill scheduler, and the
 * row-claiming query used to hand a board to a player.
 */
@Repository
public interface GeneratedBoardRepository extends JpaRepository<GeneratedBoard, Long> {

    /**
     * Counts the pooled boards currently available at the given difficulty.
     *
     * @param difficulty the difficulty tier to count
     * @return the number of pooled boards at that difficulty
     */
    long countByDifficulty(BoardDifficulty difficulty);

    /**
     * Claims the oldest pooled board at the given difficulty, locking its row for the duration of
     * the caller's transaction so no other request can claim the same board. {@code SKIP LOCKED}
     * makes a concurrent claimer step over an already-locked row and take the next one instead of
     * blocking on it — without it, simultaneous requests would serialize on the same row and then
     * find it deleted. Requires MySQL 8.0+.
     *
     * <p>Native rather than JPQL because the locking clause is dialect-specific; the difficulty is
     * therefore passed as its raw DB string ({@link BoardDifficulty#dbValue()}) rather than going
     * through {@code BoardDifficultyConverter}.
     *
     * <p>The caller is expected to delete the claimed row — the pool is consumed as boards are
     * served, which is what {@link BoardGenerationService}'s low-watermark refill reacts to.
     *
     * @param difficulty the raw DB value of the difficulty tier (e.g. {@code "moderate"})
     * @return the claimed board, or empty if the pool holds none at that difficulty
     */
    @Query(value = """
            SELECT * FROM generated_boards
            WHERE difficulty_rating = :difficulty
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<GeneratedBoard> claimNextByDifficulty(@Param("difficulty") String difficulty);
}
