package com.cortex_clash.mind_games.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically removes expired refresh tokens so the table doesn't grow without bound.
 * Runs on the schedule given by {@code app.jwt.refresh-cleanup-cron}.
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Constructs a new refresh token cleanup job.
     *
     * @param refreshTokenRepository the repository used to delete expired refresh tokens
     */
    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Deletes all refresh tokens that have expired before the current time.
     * Runs on a schedule configured by the {@code app.jwt.refresh-cleanup-cron} property.
     * Executes within a transaction to ensure atomicity of the bulk delete operation.
     */
    @Scheduled(cron = "${app.jwt.refresh-cleanup-cron}")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredBefore(Instant.now());
        log.info("Purged {} expired refresh token(s)", deleted);
    }
}
