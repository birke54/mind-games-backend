package com.cortex_clash.mind_games.mail;

import com.cortex_clash.mind_games.domain.User;

/**
 * Raised when a reset token has been issued and the link is ready to be delivered.
 *
 * <p>The event exists so that the token flow and the SMTP conversation happen on different threads:
 * {@link PasswordResetMailDispatcher} handles it after the issuing transaction commits, which is
 * what keeps the reset endpoint's response time from depending on whether the address had an
 * account. See {@code PasswordResetService.requestReset}.
 *
 * <p>{@code resetLink} carries the raw token — the only copy in existence — so this event is a
 * bearer secret. It never leaves the process, and it must not be logged.
 *
 * @param recipient the account the reset was requested for
 * @param resetLink the fully-formed link, token included
 */
public record PasswordResetLinkIssued(User recipient, String resetLink) {}
