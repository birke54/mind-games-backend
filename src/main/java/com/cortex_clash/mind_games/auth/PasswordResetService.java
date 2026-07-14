package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.AuditAction;
import com.cortex_clash.mind_games.domain.PasswordResetToken;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserAudit;
import com.cortex_clash.mind_games.domain.UserStatus;
import com.cortex_clash.mind_games.mail.PasswordResetLinkIssued;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.cortex_clash.mind_games.auth.OpaqueTokens.sha256;

/**
 * Issues and redeems password-reset tokens.
 *
 * <p>Deliberately close to {@link RefreshTokenService} — 32 bytes of {@link java.security.SecureRandom},
 * stored as {@code sha256(raw)}, raw value never persisted — and differs from it in exactly three
 * ways, all of them intentional:
 *
 * <ul>
 *   <li><strong>TTL is 30 minutes</strong>, not 30 days. A reset link is a live account-takeover
 *       secret sitting in an inbox; it should stop being one quickly.</li>
 *   <li><strong>Single use.</strong> Redemption stamps {@code usedAt} and the token is dead.</li>
 *   <li><strong>Issuing invalidates any outstanding token</strong> for that user, so a user has at
 *       most one live link and an older, possibly-intercepted one cannot be redeemed after they
 *       ask for a fresh one.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final UserAuditRepository userAuditRepository;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher events;
    private final PasswordEncoder passwordEncoder;

    /** How long a reset link stays redeemable. */
    private final long tokenExpirationMinutes;

    /** Per-user cap on reset requests per hour. Without one this endpoint is an email cannon. */
    private final int maxRequestsPerHour;

    /** Front-end route the emailed link points at; the token is appended as a query parameter. */
    private final String resetUrl;

    public PasswordResetService(PasswordResetTokenRepository passwordResetTokenRepository,
                                UserRepository userRepository,
                                UserAuditRepository userAuditRepository,
                                RefreshTokenService refreshTokenService,
                                ApplicationEventPublisher events,
                                @Value("${app.password-reset.token-expiration-minutes}") long tokenExpirationMinutes,
                                @Value("${app.password-reset.max-requests-per-hour}") int maxRequestsPerHour,
                                @Value("${app.password-reset.reset-url}") String resetUrl) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.userAuditRepository = userAuditRepository;
        this.refreshTokenService = refreshTokenService;
        this.events = events;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.tokenExpirationMinutes = tokenExpirationMinutes;
        this.maxRequestsPerHour = maxRequestsPerHour;
        this.resetUrl = resetUrl;
    }

    /**
     * Begins a reset for whoever owns {@code email}: invalidates any outstanding token, issues a
     * fresh one, and raises {@link PasswordResetLinkIssued} so the link goes out.
     *
     * <p><strong>This method does not report whether the address has an account.</strong> It
     * returns normally either way, and the caller answers 202 either way. {@code users.email} is
     * UNIQUE, so a confirmed address is a confirmed account, and any observable difference between
     * the two paths — a status code, a body, an error — turns this endpoint into a user-enumeration
     * oracle. The only thing that distinguishes them is that one of them sends an email, which is
     * unavoidable and is why the address's owner is the only person who learns anything.
     *
     * <p><strong>Response time is part of that.</strong> The send is not performed here — it is
     * handed to {@link com.cortex_clash.mind_games.mail.PasswordResetMailDispatcher} on a pool
     * thread — because doing it inline made the two paths take visibly different amounts of time: an
     * unknown address returned after one SELECT, a known one after an SMTP round trip to SES. Same
     * 202, same body, and an attacker with a stopwatch could tell them apart anyway. What remains is
     * a few local writes against a difference that used to be a conversation with another host.
     *
     * <p>Requests beyond the hourly cap are likewise silently dropped: telling the caller they have
     * been rate-limited would leak the same fact.
     *
     * @param email the address the reset was requested for; may belong to no account
     */
    @Transactional
    public void requestReset(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // Deliberately silent. See above: saying anything here is the whole vulnerability.
            log.info("Password reset requested for an address with no account");
            return;
        }
        if (isOverRateLimit(user)) {
            log.warn("Password reset rate limit hit for user: {}", user.getUsername());
            return;
        }

        // Any link already in flight stops working the moment a new one is asked for.
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), Instant.now());

        String rawToken = OpaqueTokens.generate();
        passwordResetTokenRepository.save(new PasswordResetToken(
                user.getId(),
                sha256(rawToken),
                Instant.now().plus(tokenExpirationMinutes, ChronoUnit.MINUTES)));
        userAuditRepository.save(
                new UserAudit(user.getUsername(), AuditAction.PASSWORD_RESET_REQUESTED));

        // The raw token leaves here and is never recoverable again: only its hash was stored. The
        // dispatcher picks the event up after this transaction commits, so a send never races the
        // row it depends on, and a failed one cannot reach the caller: it has already been answered.
        events.publishEvent(new PasswordResetLinkIssued(user, resetUrl + "?token=" + rawToken));
        log.info("Password reset link issued for user: {}", user.getUsername());
    }

    /**
     * Redeems a reset token and sets the user's new password.
     *
     * <p>Three things happen alongside the password change, and each is load-bearing:
     *
     * <ul>
     *   <li><strong>Every session is revoked.</strong> If the reset is happening because the account
     *       was compromised, leaving the attacker's refresh-token family alive defeats the exercise.</li>
     *   <li><strong>The lockout is cleared</strong> — status back to active, failed attempts to zero.
     *       Being locked out by failed attempts is the single most common reason a real person
     *       resets their password. Skip this and they set a new password and still cannot log in,
     *       and the symptom ("my new password doesn't work") points at exactly the wrong place.</li>
     *   <li><strong>The token is spent.</strong> Single use, enforced by {@code usedAt}.</li>
     * </ul>
     *
     * @param rawToken    the token from the emailed link
     * @param newPassword the password to set
     * @throws InvalidResetTokenException if the token is unknown, already used, or expired
     * @throws InvalidPasswordException   if the new password fails {@link PasswordPolicy}
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(sha256(rawToken))
                .orElse(null);
        if (token == null) {
            log.warn("Password reset attempted with an unknown token");
            throw new InvalidResetTokenException("Unknown reset token");
        }
        if (token.getUsedAt() != null) {
            log.warn("Password reset attempted with an already-used token: userId {}", token.getUserId());
            throw new InvalidResetTokenException("Reset token already used");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Password reset attempted with an expired token: userId {}", token.getUserId());
            throw new InvalidResetTokenException("Reset token expired");
        }

        User user = userRepository.findById(token.getUserId()).orElse(null);
        if (user == null) {
            // The FK is ON DELETE CASCADE, so this should be unreachable; treat it as a bad token
            // rather than a 500.
            log.warn("Reset token for unknown user: userId {}", token.getUserId());
            throw new InvalidResetTokenException("Reset token for unknown user");
        }

        // Validate before spending the token, so a rejected password does not cost the user their
        // link and force them back to their inbox.
        PasswordPolicy.validate(newPassword);

        token.markUsed();
        passwordResetTokenRepository.save(token);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedAttempts(0);
        userRepository.save(user);

        refreshTokenService.revokeAllForUser(user.getId());
        userAuditRepository.save(
                new UserAudit(user.getUsername(), AuditAction.PASSWORD_RESET_COMPLETED));
        log.info("Password reset completed for user: {}", user.getUsername());
    }

    /** The reset token lifetime. */
    public Duration tokenTtl() {
        return Duration.ofMinutes(tokenExpirationMinutes);
    }

    /**
     * Whether this user has already asked for more resets than the cap allows in the last hour.
     * Every request issues a row, so the row count within the window is the request count.
     */
    private boolean isOverRateLimit(User user) {
        long recent = passwordResetTokenRepository.countByUserIdAndCreatedAtAfter(
                user.getId(), Instant.now().minus(1, ChronoUnit.HOURS));
        return recent >= maxRequestsPerHour;
    }
}
