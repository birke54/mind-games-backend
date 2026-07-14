package com.cortex_clash.mind_games.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands a {@link PasswordResetLinkIssued} to the {@link PasswordResetMailer}, off the request
 * thread and after the issuing transaction has committed.
 *
 * <p><strong>Off the request thread</strong> because sending is what made the reset endpoint
 * distinguishable. The endpoint answers 202 for every address, registered or not, so that it cannot
 * be used to enumerate accounts — but an SMTP round trip to SES takes long enough to see, and only
 * addresses with an account paid for one. Same status code, measurably different response time, same
 * oracle. Handling the send here means the caller is answered without waiting for it, and what is
 * left of the difference is a couple of local DB writes rather than a conversation with another host.
 *
 * <p><strong>After commit</strong> because a link mailed from inside the issuing transaction is a
 * link that outlives a rollback: the user would receive a token that was never persisted, and a
 * reset flow that is broken in a way they cannot do anything about. {@code fallbackExecution} keeps
 * a caller with no transaction working rather than silently dropping the mail, which is the default.
 */
@Component
public class PasswordResetMailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailDispatcher.class);

    private final PasswordResetMailer mailer;

    /**
     * Constructs the dispatcher.
     *
     * @param mailer the delivery mechanism — SMTP in a deployment, the log locally
     */
    public PasswordResetMailDispatcher(PasswordResetMailer mailer) {
        this.mailer = mailer;
    }

    /**
     * Delivers the link.
     *
     * <p>A failed send is logged and goes no further. It has nowhere to go: this runs on a pool
     * thread with no caller to return to, and the user was answered 202 long before it ran. They can
     * ask for another link; the dead token row expires in minutes and is harmless in the meantime —
     * the raw token only ever existed in the mail that failed to send. The log line is the signal to
     * alarm on, and the only one there is.
     *
     * @param event the recipient and the link, token included
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordResetLinkIssued(PasswordResetLinkIssued event) {
        try {
            mailer.sendResetLink(event.recipient(), event.resetLink());
        } catch (RuntimeException e) {
            log.error("Password reset email failed to send for user: {}",
                    event.recipient().getUsername(), e);
        }
    }
}
