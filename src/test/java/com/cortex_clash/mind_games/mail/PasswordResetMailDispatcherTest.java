package com.cortex_clash.mind_games.mail;

import com.cortex_clash.mind_games.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PasswordResetMailDispatcherTest {

    private static final String LINK = "https://cortexclash.org/reset-password?token=raw-token";

    private final PasswordResetMailer mailer = mock(PasswordResetMailer.class);
    private final PasswordResetMailDispatcher dispatcher = new PasswordResetMailDispatcher(mailer);

    private User alice() {
        return new User("alice", "alice@example.com",
                new BCryptPasswordEncoder().encode("old password"));
    }

    @Test
    void theLinkIsHandedToTheMailer() {
        User alice = alice();

        dispatcher.onPasswordResetLinkIssued(new PasswordResetLinkIssued(alice, LINK));

        verify(mailer).sendResetLink(alice, LINK);
    }

    @Test
    void aFailedSendIsLoggedAndGoesNoFurther() {
        doThrow(new IllegalStateException("SES is down"))
                .when(mailer).sendResetLink(any(User.class), anyString());

        // Nothing to throw at: this runs on a pool thread after the transaction committed, and the
        // caller was answered 202 before it started. Letting it out would only produce an uncaught
        // exception in the executor. The user can ask for another link; the log line is the signal.
        assertDoesNotThrow(() ->
                dispatcher.onPasswordResetLinkIssued(new PasswordResetLinkIssued(alice(), LINK)));
    }
}
