package com.cortex_clash.mind_games;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point. {@code @EnableScheduling} activates the {@code @Scheduled} jobs —
 * the board-pool refill ({@link com.cortex_clash.mind_games.generate.BoardGenerationService}) and
 * the refresh-token cleanup ({@link com.cortex_clash.mind_games.auth.RefreshTokenCleanupJob}).
 */
@SpringBootApplication
@EnableScheduling
public class MindGamesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindGamesApplication.class, args);
    }

}
