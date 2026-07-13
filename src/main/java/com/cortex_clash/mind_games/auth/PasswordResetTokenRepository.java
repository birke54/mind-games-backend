package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for managing {@link PasswordResetToken} entities.
 *
 * <p>Provides token lookup by hash, bulk invalidation of a user's outstanding tokens, the
 * per-user request count behind the rate limit, and cleanup of expired rows.
 */
@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * Finds a reset token by its hashed value.
     *
     * @param tokenHash the SHA-256 hash of the token
     * @return an {@link Optional} containing the token if found, or empty otherwise
     */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Marks every outstanding token for a user as used, killing it.
     *
     * <p>Called on reissue, so a user has at most one live reset link, and on redemption, so the
     * token just spent cannot be spent again.
     *
     * @param userId the id of the user whose tokens should be invalidated
     * @param now    the instant to record as the use time
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now "
            + "where t.userId = :userId and t.usedAt is null")
    void invalidateAllForUser(@Param("userId") Integer userId, @Param("now") Instant now);

    /**
     * Counts the tokens issued to a user since a cutoff, used or not. This is the rate limit's
     * view of "requests in the last hour": every request issues a row, so the row count is the
     * request count.
     *
     * @param userId the user to count for
     * @param since  the start of the window
     * @return the number of tokens issued to that user within the window
     */
    long countByUserIdAndCreatedAtAfter(Integer userId, Instant since);

    /**
     * Deletes tokens whose expiry has passed. Reset tokens are single-use and short-lived, so an
     * expired row is dead in every sense and nothing is learned by keeping it.
     *
     * @param cutoff tokens expiring before this instant are removed
     * @return the number of rows removed
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
