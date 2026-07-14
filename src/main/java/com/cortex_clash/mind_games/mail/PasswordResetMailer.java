package com.cortex_clash.mind_games.mail;

import com.cortex_clash.mind_games.domain.User;

/**
 * Delivers a password-reset link to its owner.
 *
 * <p>This is the seam between the token flow and actually getting mail out of the ECS task, which
 * are separable problems: the token flow is finished and testable, mail requires SES to be out of
 * sandbox mode. Until an SMTP implementation lands, {@link LoggingPasswordResetMailer} writes the
 * link to the log, and the flow works end to end for anyone who can read the log.
 *
 * <p>Implementations must treat the link as a bearer secret: it is the only copy of the raw token
 * in existence, and anyone holding it can take over the account.
 */
public interface PasswordResetMailer {

    /**
     * Sends {@code resetLink} to the user's registered email address.
     *
     * @param recipient the account the reset was requested for
     * @param resetLink the fully-formed link, token included, for the user to click
     */
    void sendResetLink(User recipient, String resetLink);
}
