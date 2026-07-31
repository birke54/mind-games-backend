package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.api.ApiController;
import com.cortex_clash.mind_games.domain.RefreshToken;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.metrics.MetricName;
import com.cortex_clash.mind_games.metrics.MetricsService;
import com.cortex_clash.mind_games.metrics.TagName;
import com.cortex_clash.mind_games.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.security.auth.login.CredentialException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static com.cortex_clash.mind_games.auth.OpaqueTokens.sha256;

/**
 * Issues, rotates, and revokes refresh tokens. Refresh tokens are opaque high-entropy strings
 * (not JWTs) stored only as SHA-256 hashes so they can be looked up and revoked server-side.
 *
 * <p>Rotation: each successful refresh revokes the presented token and issues a replacement in
 * the same family. Presenting an already-revoked token is treated as theft and revokes the
 * whole family, forcing re-login.
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final MetricsService metricService;
    private final long refreshExpirationDays;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               JwtService jwtService,
                               MetricsService metricsService,
                               @Value("${app.jwt.refresh-expiration-days}") long refreshExpirationDays) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.refreshExpirationDays = refreshExpirationDays;
        this.metricService = metricsService;
    }

    /** Issues a brand-new refresh token in a fresh family. Returns the raw token for the client. */
    @Transactional
    public String issue(User user) {
        return issueInFamily(user, UUID.randomUUID().toString());
    }

    /**
     * Validates the presented refresh token and rotates it: the old token is revoked and a new
     * one (plus a new access JWT) is returned. Detects reuse and locked accounts.
     */
    @Transactional
    public TokenPair rotate(String rawToken) throws CredentialException {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .orElse(null);
        if (stored == null) {
            log.warn("Invalid refresh token");
            metricService.recordCounter(MetricName.REFRESH_TOKEN_ERROR_COUNT, Map.of(TagName.REASON, RefreshTokenFailureReason.INVALID_TOKEN));
            throw new CredentialException("Invalid refresh token");
        }
        if (stored.getRevokedAt() != null) {
            // A superseded token was replayed — likely stolen. Revoke the entire lineage. This is
            // checked before the user lookup: reuse detection is about the token's lineage and must
            // not depend on the user still existing.
            log.warn("Refresh token reuse detected: userId {}, familyId {}", stored.getUserId(), stored.getFamilyId());
            metricService.recordCounter(MetricName.REFRESH_TOKEN_ERROR_COUNT, Map.of(TagName.REASON, RefreshTokenFailureReason.REVOKED_TOKEN));
            refreshTokenRepository.revokeFamily(stored.getFamilyId(), Instant.now());
            throw new CredentialException("Refresh token reuse detected");
        }
        User user = userRepository.findById(stored.getUserId())
            .orElse(null);
        if (user == null) {
            log.warn("Refresh token for unknown: userId {}, familyId {}", stored.getUserId(), stored.getFamilyId());

            throw new CredentialException("Refresh token for unknown user");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Refresh token expired: userId {}, familyId {}", stored.getUserId(), stored.getFamilyId());
            throw new CredentialException("Refresh token expired");
        }
        if (user.isLockedOut()) {
            log.warn("Account locked for user: {}", user.getUsername());
            refreshTokenRepository.revokeAllActiveForUser(stored.getUserId(), Instant.now());
            throw new CredentialException("Account is locked");
        }

        stored.revoke();
        stored.markUsed();
        refreshTokenRepository.save(stored);

        String newRefreshToken = issueInFamily(user, stored.getFamilyId());
        log.info("Refresh token rotated for user: {}", user.getUsername());
        return new TokenPair(jwtService.issueToken(user), newRefreshToken);
    }

    /** Revokes a single refresh token (logout of the current session). No-op if unknown. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(sha256(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.revoke();
                refreshTokenRepository.save(token);
                log.info("Refresh token revoked for user: {}", token.getUserId());
            }
        });
    }

    /** Revokes every active refresh token for a user (e.g. on lockout or password change). */
    @Transactional
    public void revokeAllForUser(Integer userId) {
        refreshTokenRepository.revokeAllActiveForUser(userId, Instant.now());
        log.info("All active refresh tokens revoked for user: {}", userId);
    }

    /** The refresh token lifetime, used to set the cookie's Max-Age. */
    public Duration refreshTokenTtl() {
        return Duration.ofDays(refreshExpirationDays);
    }

    /**
     * Issues a new refresh token within an existing token family.
     *
     * @param user the user for whom to issue the token
     * @param familyId the family identifier to group related tokens
     * @return the raw token string to be sent to the client
     */
    private String issueInFamily(User user, String familyId) {
        String rawToken = OpaqueTokens.generate();
        RefreshToken token = new RefreshToken(
                user.getId(),
                familyId,
                sha256(rawToken),
                Instant.now().plus(refreshExpirationDays, ChronoUnit.DAYS));
        refreshTokenRepository.save(token);
        log.info("New refresh token issued for user: {}, familyId: {}", user.getUsername(), familyId);
        return rawToken;
    }
}
