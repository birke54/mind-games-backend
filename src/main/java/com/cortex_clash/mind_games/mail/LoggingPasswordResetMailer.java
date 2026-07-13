package com.cortex_clash.mind_games.mail;

import com.cortex_clash.mind_games.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes the reset link to the log instead of emailing it. Active while
 * {@code app.password-reset.mailer} is {@code log}, which is the default until an SMTP
 * implementation lands.
 *
 * <p>This exists so the token flow can ship before mail does. It is a stand-in, and it is not a
 * safe one for production: <strong>the log line contains a live account-takeover secret</strong>,
 * readable by anyone with access to CloudWatch. Hence the warning level — this should be noisy, and
 * it should be embarrassing to leave switched on.
 */
@Component
@ConditionalOnProperty(name = "app.password-reset.mailer", havingValue = "log", matchIfMissing = true)
public class LoggingPasswordResetMailer implements PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetMailer.class);

    @Override
    public void sendResetLink(User recipient, String resetLink) {
        log.warn("No mailer configured — reset link for user {} NOT sent, logged instead: {}",
                recipient.getUsername(), resetLink);
    }
}
