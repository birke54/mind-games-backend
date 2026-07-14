package com.cortex_clash.mind_games;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point. {@code @EnableScheduling} activates the {@code @Scheduled} jobs —
 * the board-pool refill ({@link com.cortex_clash.mind_games.generate.BoardGenerationService}), the
 * refresh-token cleanup ({@link com.cortex_clash.mind_games.auth.RefreshTokenCleanupJob}), and the
 * password-reset-token cleanup
 * ({@link com.cortex_clash.mind_games.auth.PasswordResetTokenCleanupJob}).
 *
 * <p>{@code @EnableAsync} activates the {@code @Async} handlers, of which there is currently one:
 * {@link com.cortex_clash.mind_games.mail.PasswordResetMailDispatcher} sends the reset mail on a
 * pool thread so the reset endpoint's response time does not give away whether the address had an
 * account. Without this annotation the handler runs inline on the request thread and that property
 * is quietly lost — the flow still works, which is what makes it worth saying out loud.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MindGamesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindGamesApplication.class, args);
    }

}
