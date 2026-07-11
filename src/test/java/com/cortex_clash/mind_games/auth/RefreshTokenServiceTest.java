package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.RefreshToken;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserStatus;
import com.cortex_clash.mind_games.security.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

import javax.security.auth.login.CredentialException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final Integer USER_ID = 1;

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtService jwtService = new JwtService(SECRET, 60);
    private final RefreshTokenService service =
            new RefreshTokenService(refreshTokenRepository, userRepository, jwtService, 30);

    private RefreshToken storedToken() {
        return new RefreshToken(USER_ID, "family-1", "hash",
                Instant.now().plus(1, ChronoUnit.DAYS));
    }

    @Test
    void rotateRevokesOldTokenAndIssuesReplacementInSameFamily() throws Exception {
        RefreshToken current = storedToken();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(current));
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(new User("alice", "alice@example.com", "hash")));

        TokenPair result = service.rotate("some-raw-token");

        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertNotNull(current.getRevokedAt(), "old token must be revoked on rotation");

        // Two saves: the revoked old token, plus the freshly issued replacement.
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(saved.capture());
        RefreshToken replacement = saved.getAllValues().get(1);
        assertEquals("family-1", replacement.getFamilyId(), "replacement stays in the same family");
    }

    @Test
    void rotateOfRevokedTokenIsReuseAndRevokesWholeFamily() {
        RefreshToken current = storedToken();
        current.revoke(); // already superseded
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(current));

        assertThrows(CredentialException.class, () -> service.rotate("replayed-token"));

        verify(refreshTokenRepository).revokeFamily(eq("family-1"), any(Instant.class));
        verify(userRepository, never()).findById(any());
    }

    @Test
    void rotateForLockedUserIsRejectedAndRevokesAllTheirTokens() {
        RefreshToken current = storedToken();
        User locked = new User("bob", "bob@example.com", "hash");
        locked.setStatus(UserStatus.LOCKED_OUT);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(current));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(locked));

        assertThrows(CredentialException.class, () -> service.rotate("valid-token"));

        verify(refreshTokenRepository).revokeAllActiveForUser(eq(USER_ID), any(Instant.class));
    }

    @Test
    void rotateOfUnknownTokenIsRejected() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThrows(CredentialException.class, () -> service.rotate("nonexistent"));
    }
}
