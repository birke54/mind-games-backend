package com.cortex_clash.mind_games.api.requests;

import com.cortex_clash.mind_games.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for starting a password reset.
 *
 * <p>Note the absence of an {@code @Email} format check. The endpoint answers 202 whatever it is
 * given, so rejecting a malformed address would buy nothing and would introduce a response that
 * differs by input — the exact thing this endpoint is built not to do. An address that is not an
 * address simply matches no account.
 *
 * @param email the address to send the reset link to; may belong to no account
 */
public record RequestPasswordReset(
        @NotBlank @Size(max = User.EMAIL_MAX_LENGTH) String email
) {}
