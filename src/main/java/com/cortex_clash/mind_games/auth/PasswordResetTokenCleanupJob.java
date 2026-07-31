package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.metrics.MetricName;
import com.cortex_clash.mind_games.metrics.MetricsService;
import com.cortex_clash.mind_games.metrics.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Periodically removes expired password-reset tokens so the table doesn't grow without bound.
 * Runs on the schedule given by {@code app.password-reset.cleanup-cron}.
 *
 * <p>Unlike {@link RefreshTokenCleanupJob}, which keeps revoked-but-unexpired rows so that reuse
 * detection still fires, this one can delete freely: reset tokens are single-use and expire in
 * minutes, so an expired row is dead in every sense and detects nothing by sticking around.
 */
@Component
public class PasswordResetTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenCleanupJob.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final MetricsService metricsService;

    /**
     * Constructs a new password-reset token cleanup job.
     *
     * @param passwordResetTokenRepository the repository used to delete expired reset tokens
     */
    public PasswordResetTokenCleanupJob(PasswordResetTokenRepository passwordResetTokenRepository, MetricsService metricsService) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.metricsService = metricsService;
    }

    /**
     * Deletes all password-reset tokens that expired before the current time.
     */
    @Scheduled(cron = "${app.password-reset.cleanup-cron}")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = passwordResetTokenRepository.deleteExpiredBefore(Instant.now());
        log.info("Purged {} expired password reset token(s)", deleted);
        metricsService.recordCounter(MetricName.PASSWORD_RESET_CLEANUP_SUCCESS_COUNT, null);
    }
}
