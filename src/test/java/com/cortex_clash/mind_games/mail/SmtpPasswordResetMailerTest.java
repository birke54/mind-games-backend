package com.cortex_clash.mind_games.mail;

import com.cortex_clash.mind_games.domain.User;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the message {@link SmtpPasswordResetMailer} hands to SMTP. The assertions are made against
 * a real {@link MimeMessage} rather than a mock, because everything worth checking here — the
 * headers, the two alternative parts, the escaping — is a property of the assembled message, and a
 * mock would only prove that we called the methods we called.
 */
class SmtpPasswordResetMailerTest {

    private static final String FROM = "Cortex Clash <noreply@cortexclash.org>";
    private static final String REPLY_TO = "support@cortexclash.org";
    private static final long EXPIRATION_MINUTES = 30;
    private static final String LINK = "https://cortexclash.org/reset-password?token=abc123";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    private final SmtpPasswordResetMailer mailer =
            new SmtpPasswordResetMailer(mailSender, FROM, REPLY_TO, EXPIRATION_MINUTES);

    /** A message backed by a null Session, which is all JavaMail needs to assemble one offline. */
    private MimeMessage emptyMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private User alice() {
        return new User("alice", "alice@example.com", "irrelevant-hash");
    }

    /** Sends, and hands back the message that actually went to the transport. */
    private MimeMessage send(User recipient) {
        MimeMessage message = emptyMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);

        mailer.sendResetLink(recipient, LINK);

        verify(mailSender).send(message);
        return message;
    }

    /** Every text part of the message, transfer-encoding decoded, in depth-first order. */
    private List<String> textParts(Part part) throws Exception {
        List<String> parts = new ArrayList<>();
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                parts.addAll(textParts(multipart.getBodyPart(i)));
            }
        } else if (content instanceof String text) {
            parts.add(text);
        }
        return parts;
    }

    private String plainPart(MimeMessage message) throws Exception {
        return textParts(message).stream()
                .filter(p -> !p.contains("<p>"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no plain-text part"));
    }

    private String htmlPart(MimeMessage message) throws Exception {
        return textParts(message).stream()
                .filter(p -> p.contains("<p>"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HTML part"));
    }

    @Test
    void addressesTheMessageToTheAccountOwner() throws Exception {
        MimeMessage message = send(alice());

        assertEquals("alice@example.com", message.getRecipients(MimeMessage.RecipientType.TO)[0].toString());
        assertEquals(FROM, message.getFrom()[0].toString());
        assertEquals("Reset your Cortex Clash password", message.getSubject());
    }

    /**
     * People do reply to password-reset mail ("I didn't request this"). {@code noreply@} has no
     * mailbox behind it, so without a Reply-To their answer bounces and they get a second failure
     * on top of the first.
     */
    @Test
    void repliesGoSomewhereAHumanReadsThem() throws Exception {
        MimeMessage message = send(alice());

        assertEquals(REPLY_TO, message.getReplyTo()[0].toString());
    }

    /**
     * Both alternatives carry the link. A reset mail that renders as a wall of markup in a
     * text-only client is a reset mail nobody can use, and the plain part is the one that has to
     * work when the HTML one does not.
     */
    @Test
    void bothAlternativesCarryTheLinkAndTheExpiry() throws Exception {
        MimeMessage message = send(alice());

        String plain = plainPart(message);
        assertTrue(plain.contains(LINK), "the plain-text part must carry the link");
        assertTrue(plain.contains("30 minutes"), "the user is told how long they have");

        String html = htmlPart(message);
        assertTrue(html.contains(LINK), "the HTML part must carry the link");
        assertTrue(html.contains("30 minutes"), "the user is told how long they have");
    }

    /**
     * Usernames are attacker-chosen and this one is interpolated into markup that lands in someone
     * else's inbox — the victim's, since a reset can be requested for any address.
     */
    @Test
    void theUsernameIsEscapedInTheHtmlPart() throws Exception {
        User mallory = new User("<script>alert(1)</script>", "mallory@example.com", "irrelevant-hash");

        String html = htmlPart(send(mallory));

        assertFalse(html.contains("<script>"), "an attacker-chosen username must not reach the inbox as markup");
        assertTrue(html.contains("&lt;script&gt;"), "it should arrive escaped, and legible");
    }

    /**
     * A build failure is wrapped, not swallowed. The decision about what a failed send means belongs
     * to the caller — {@code PasswordResetService} turns it into a log line rather than a 500,
     * because a 500 here would be a user-enumeration oracle.
     */
    @Test
    void aFailureToSendIsRaisedForTheCallerToDecideAbout() {
        MimeMessage message = emptyMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(new org.springframework.mail.MailSendException("SES is down"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThrows(RuntimeException.class, () -> mailer.sendResetLink(alice(), LINK));
    }
}
