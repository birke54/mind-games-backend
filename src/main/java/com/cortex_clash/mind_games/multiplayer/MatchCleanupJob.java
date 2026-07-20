package com.cortex_clash.mind_games.multiplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Closes out matches that will never end on their own.
 *
 * <p>A match has no other way to finish: the only thing that decides one is a player completing
 * their board, so a lobby nobody joins, or a race both players close the tab on, would otherwise
 * sit live forever — and a stale lobby keeps a join code reserved.
 *
 * <p>Like the rest of the scheduled work here this is in-process and assumes a single instance;
 * see the scaling note in {@code docs/aws-deployment.md}.
 */
@Component
public class MatchCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MatchCleanupJob.class);

    private final MatchService matchService;
    private final Duration lobbyTimeout;
    private final Duration idleTimeout;

    /**
     * @param matchService         the service that performs the sweep
     * @param lobbyTimeoutMinutes  how long a lobby may wait for an opponent before it is abandoned
     * @param idleTimeoutMinutes   how long a started race may run before it is presumed abandoned
     */
    public MatchCleanupJob(MatchService matchService,
                           @Value("${app.multiplayer.lobby-timeout-minutes}") long lobbyTimeoutMinutes,
                           @Value("${app.multiplayer.idle-timeout-minutes}") long idleTimeoutMinutes) {
        this.matchService = matchService;
        this.lobbyTimeout = Duration.ofMinutes(lobbyTimeoutMinutes);
        this.idleTimeout = Duration.ofMinutes(idleTimeoutMinutes);
    }

    /**
     * Abandons stale lobbies and long-dead races. Runs on the schedule given by
     * {@code app.multiplayer.cleanup-cron}.
     */
    @Scheduled(cron = "${app.multiplayer.cleanup-cron}")
    public void abandonStaleMatches() {
        Instant now = Instant.now();
        int abandoned = matchService.abandonStaleMatches(now.minus(lobbyTimeout), now.minus(idleTimeout));
        if (abandoned > 0) {
            log.info("Abandoned {} stale match(es)", abandoned);
        }
    }
}
