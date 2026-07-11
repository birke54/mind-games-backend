package com.cortex_clash.mind_games.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenCleanupJobTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final RefreshTokenCleanupJob job = new RefreshTokenCleanupJob(refreshTokenRepository);

    @Test
    void purgesUsingACutoffAtRoughlyNow() {
        when(refreshTokenRepository.deleteExpiredBefore(any())).thenReturn(3);
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        job.purgeExpiredTokens();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository).deleteExpiredBefore(cutoff.capture());
        Instant after = Instant.now().plus(1, ChronoUnit.MINUTES);
        assertTrue(cutoff.getValue().isAfter(before) && cutoff.getValue().isBefore(after),
                "cutoff should be approximately the current instant");
    }
}
