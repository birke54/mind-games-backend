package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.AuditAction;
import com.cortex_clash.mind_games.domain.PasswordResetToken;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserAudit;
import com.cortex_clash.mind_games.domain.UserStatus;
import com.cortex_clash.mind_games.mail.PasswordResetLinkIssued;
import com.cortex_clash.mind_games.metrics.MetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    private static final Integer USER_ID = 1;
    private static final String EMAIL = "alice@example.com";
    private static final String VALID_PASSWORD = "correct horse battery";
    private static final String RESET_URL = "https://cortexclash.org/reset-password";

    private final PasswordResetTokenRepository tokenRepository =
            mock(PasswordResetTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserAuditRepository userAuditRepository = mock(UserAuditRepository.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final MetricsService metricsService = mock(MetricsService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final PasswordResetService service = new PasswordResetService(
            tokenRepository, userRepository, userAuditRepository, refreshTokenService, metricsService, events,
            30, 3, RESET_URL);

    /** The link the service asked to have delivered, or a failure if it asked for no delivery. */
    private PasswordResetLinkIssued publishedEvent() {
        ArgumentCaptor<PasswordResetLinkIssued> event =
                ArgumentCaptor.forClass(PasswordResetLinkIssued.class);
        verify(events).publishEvent(event.capture());
        return event.getValue();
    }

    /**
     * A persisted-looking user. The id is set by reflection because JPA assigns it on insert and
     * {@link User} has no setter — and the service keys tokens, the rate limit, and session
     * revocation off it, so a null id would make every one of those assertions vacuous.
     */
    private User alice() {
        User user = new User("alice", EMAIL, new BCryptPasswordEncoder().encode("old password"));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    /** A token that is live: unused, and half an hour from expiring. */
    private PasswordResetToken liveToken() {
        return new PasswordResetToken(USER_ID, OpaqueTokens.sha256("raw-token"),
                Instant.now().plus(30, ChronoUnit.MINUTES));
    }

    // ---- requesting a reset ------------------------------------------------

    @Test
    void requestIssuesATokenAndAsksForTheLinkToBeMailed() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(alice()));

        service.requestReset(EMAIL);

        verify(tokenRepository).save(any(PasswordResetToken.class));

        // The service asks for the link to go out and returns; the send itself happens after commit,
        // on a pool thread. That is what keeps a registered address from taking an SMTP round trip
        // longer to answer than an unregistered one, which is the same enumeration oracle the
        // identical 202s exist to deny, read off a clock instead of a response body.
        PasswordResetLinkIssued issued = publishedEvent();
        assertEquals(EMAIL, issued.recipient().getEmail());
        assertTrue(issued.resetLink().startsWith(RESET_URL + "?token="),
                "the emailed link must carry the raw token to the front-end route");

        // The raw token must never be recoverable from what we persisted.
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());
        String rawToken = issued.resetLink().substring((RESET_URL + "?token=").length());
        assertEquals(OpaqueTokens.sha256(rawToken), saved.getValue().getTokenHash(),
                "only the hash of the raw token is stored");
    }

    @Test
    void requestForUnknownEmailIssuesNothingAndSaysNothing() {
        when(userRepository.findByEmail("stranger@example.com")).thenReturn(Optional.empty());

        // The point: this does not throw. The controller answers 202 either way, so an unregistered
        // address is indistinguishable from a registered one.
        service.requestReset("stranger@example.com");

        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void requestInvalidatesAnyOutstandingTokenForThatUser() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(alice()));

        service.requestReset(EMAIL);

        // An older link — possibly intercepted — stops working the moment a new one is asked for.
        verify(tokenRepository).invalidateAllForUser(eq(USER_ID), any(Instant.class));
    }

    @Test
    void requestBeyondTheHourlyCapIsSilentlyDropped() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(alice()));
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any(Instant.class)))
                .thenReturn(3L); // cap is 3

        service.requestReset(EMAIL);

        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void requestUnderTheHourlyCapStillSends() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(alice()));
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any(Instant.class)))
                .thenReturn(2L);

        service.requestReset(EMAIL);

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(events).publishEvent(any(PasswordResetLinkIssued.class));
    }

    // ---- redeeming a reset -------------------------------------------------

    @Test
    void resetSetsTheNewPasswordSpendsTheTokenAndKillsEverySession() {
        User alice = alice();
        String oldHash = alice.getPasswordHash();
        PasswordResetToken token = liveToken();
        when(tokenRepository.findByTokenHash(OpaqueTokens.sha256("raw-token")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(alice));

        service.resetPassword("raw-token", VALID_PASSWORD);

        assertNotNull(token.getUsedAt(), "the token must be spent — single use");
        assertTrue(new BCryptPasswordEncoder().matches(VALID_PASSWORD, alice.getPasswordHash()),
                "the new password must be what is now stored");
        assertNotEquals(oldHash, alice.getPasswordHash());

        // If the reset is happening because the account was compromised, leaving the attacker's
        // refresh-token family alive would defeat the entire exercise.
        verify(refreshTokenService).revokeAllForUser(USER_ID);

        ArgumentCaptor<UserAudit> audit = ArgumentCaptor.forClass(UserAudit.class);
        verify(userAuditRepository).save(audit.capture());
        assertEquals(AuditAction.PASSWORD_RESET_COMPLETED, audit.getValue().getAction());
    }

    @Test
    void resetClearsTheLockoutThatSentThemHereInTheFirstPlace() {
        User locked = alice();
        locked.setStatus(UserStatus.LOCKED_OUT);
        locked.setFailedAttempts(5);
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveToken()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(locked));

        service.resetPassword("raw-token", VALID_PASSWORD);

        // Being locked out by failed attempts is the most common reason a real person resets their
        // password. Not clearing it means the new password "doesn't work" and the symptom points at
        // exactly the wrong place.
        assertEquals(UserStatus.ACTIVE, locked.getStatus());
        assertEquals(0, locked.getFailedAttempts());
    }

    @Test
    void resetWithAnUnknownTokenIsRejected() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThrows(InvalidResetTokenException.class,
                () -> service.resetPassword("never-issued", VALID_PASSWORD));

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetWithAnAlreadyUsedTokenIsRejected() {
        PasswordResetToken spent = liveToken();
        spent.markUsed();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(spent));

        assertThrows(InvalidResetTokenException.class,
                () -> service.resetPassword("raw-token", VALID_PASSWORD));

        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(anyInt());
    }

    @Test
    void resetWithAnExpiredTokenIsRejected() {
        PasswordResetToken expired = new PasswordResetToken(USER_ID, "hash",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThrows(InvalidResetTokenException.class,
                () -> service.resetPassword("raw-token", VALID_PASSWORD));

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetToAWeakPasswordIsRejectedWithoutSpendingTheToken() {
        PasswordResetToken token = liveToken();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(alice()));

        assertThrows(InvalidPasswordException.class, () -> service.resetPassword("raw-token", "short"));

        // A rejected password must not cost the user their link and send them back to their inbox.
        assertNull(token.getUsedAt());
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetToTheCurrentPasswordIsRejectedWithoutSpendingTheToken() {
        User alice = alice(); // current password is "old password"
        PasswordResetToken token = liveToken();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        // The point of this branch: resetting "back" to the password you already have is refused —
        // and, like any rejected password, must not burn the link or kill the user's sessions.
        assertThrows(InvalidPasswordException.class,
                () -> service.resetPassword("raw-token", "old password"));

        assertNull(token.getUsedAt());
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(anyInt());
    }

    @Test
    void tokenTtlIsThirtyMinutes() {
        assertEquals(30, service.tokenTtl().toMinutes());
    }
}
