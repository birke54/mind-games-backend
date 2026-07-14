package com.cortex_clash.mind_games.mail;

import com.cortex_clash.mind_games.domain.User;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Sends the reset link over SMTP. Active when {@code app.password-reset.mailer} is {@code smtp};
 * in production that points at SES's SMTP endpoint, and in CI at a Mailpit container. Same code
 * path in both — only {@code spring.mail.host} differs, which is the point of speaking SMTP rather
 * than calling the SES SDK.
 *
 * <p>The message is sent as multipart/alternative: a plain-text part and an HTML part carrying the
 * same link. Plain text is not a courtesy — a reset mail that renders as a wall of markup in a
 * text-only client is a reset mail nobody can use, and spam filters treat HTML-only transactional
 * mail with suspicion.
 */
@Component
@ConditionalOnProperty(name = "app.password-reset.mailer", havingValue = "smtp")
public class SmtpPasswordResetMailer implements PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetMailer.class);

    private final JavaMailSender mailSender;

    /** Envelope/header From. A verified SES domain identity authorises every address at it. */
    private final String from;

    /**
     * Where replies go. People do reply to password-reset mail ("I didn't request this"), and with
     * no forwarding rule the reply bounces, handing a confused user a second failure on top of the
     * first. {@code noreply@} needs no mailbox; this address does.
     */
    private final String replyTo;

    /** Quoted in the copy so the user knows how long they have. */
    private final long expirationMinutes;

    public SmtpPasswordResetMailer(
            JavaMailSender mailSender,
            @Value("${app.password-reset.from}") String from,
            @Value("${app.password-reset.reply-to}") String replyTo,
            @Value("${app.password-reset.token-expiration-minutes}") long expirationMinutes) {
        this.mailSender = mailSender;
        this.from = from;
        this.replyTo = replyTo;
        this.expirationMinutes = expirationMinutes;
    }

    @Override
    public void sendResetLink(User recipient, String resetLink) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setReplyTo(replyTo);
            helper.setTo(recipient.getEmail());
            helper.setSubject("Reset your Cortex Clash password");
            helper.setText(plainText(recipient, resetLink), html(recipient, resetLink));
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException e) {
            // Wrapped rather than swallowed: the caller decides what a failed send means, and it
            // must not become a 500 (see PasswordResetService.requestReset).
            throw new IllegalStateException("Could not build the password reset message", e);
        }
        // Never log the link itself — it is a live account-takeover secret.
        log.info("Password reset email sent to user: {}", recipient.getUsername());
    }

    private String plainText(User recipient, String resetLink) {
        return """
                Hi %s,

                Someone asked to reset the password on your Cortex Clash account. If that was you,
                open the link below to choose a new one. It expires in %d minutes and can only be
                used once.

                %s

                If it wasn't you, you can ignore this email — your password has not changed, and
                nobody can reset it without this link.
                """.formatted(recipient.getUsername(), expirationMinutes, resetLink);
    }

    private String html(User recipient, String resetLink) {
        // Deliberately plain markup: no images, no external CSS, no tracking pixel. Transactional
        // mail that looks like marketing mail gets filed like marketing mail, and this one has to
        // arrive.
        return """
                <p>Hi %s,</p>
                <p>Someone asked to reset the password on your Cortex Clash account. If that was you,
                   choose a new one here:</p>
                <p><a href="%s">Reset your password</a></p>
                <p>The link expires in %d minutes and can only be used once.</p>
                <p>If it wasn't you, you can ignore this email — your password has not changed, and
                   nobody can reset it without this link.</p>
                """.formatted(escape(recipient.getUsername()), resetLink, expirationMinutes);
    }

    /**
     * Escapes the username for the HTML part. Usernames are attacker-chosen, and this one is being
     * interpolated into markup that lands in someone's inbox.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
