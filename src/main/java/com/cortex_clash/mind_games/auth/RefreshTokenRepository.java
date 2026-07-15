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

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every active token in a family at once. When a rotated-away token is replayed the
     * whole family is revoked, so a stolen token cannot outlive the theft being detected.
     */
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now "
            + "where r.familyId = :familyId and r.revokedAt is null")
    void revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** Revokes every active token for a user at once — used on lockout, logout-all, and password change. */
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
