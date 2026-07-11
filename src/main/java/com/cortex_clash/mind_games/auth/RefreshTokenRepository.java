package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for managing {@link RefreshToken} entities.
 *
 * <p>Provides custom queries for token lookup, family-based revocation, user-wide revocation,
 * and cleanup of expired tokens.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Finds a refresh token by its hashed value.
     *
     * @param tokenHash the SHA-256 hash of the token
     * @return an {@link Optional} containing the token if found, or empty otherwise
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes all active tokens belonging to a specific token family.
     *
     * <p>Used for refresh token rotation security: when a token is reused, its entire family
     * is revoked to prevent further misuse.
     *
     * @param familyId the family identifier shared by rotation chain tokens
     * @param now      the instant to record as the revocation time
     */
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now "
            + "where r.familyId = :familyId and r.revokedAt is null")
    void revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /**
     * Revokes all active refresh tokens for a specific user.
     *
     * <p>Typically used during logout-all-sessions or security events like password changes.
     *
     * @param userId the ID of the user whose tokens should be revoked
     * @param now    the instant to record as the revocation time
     */
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now "
            + "where r.userId = :userId and r.revokedAt is null")
    void revokeAllActiveForUser(@Param("userId") Integer userId, @Param("now") Instant now);

    /**
     * Deletes tokens whose expiry has passed. Revoked-but-unexpired tokens are intentionally
     * kept so that reuse/theft detection still fires during their original lifetime.
     *
     * @return the number of rows removed.
     */
    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
