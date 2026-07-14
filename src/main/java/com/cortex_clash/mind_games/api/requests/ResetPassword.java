package com.cortex_clash.mind_games.api.requests;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for redeeming a reset token and setting a new password.
 *
 * <p>The password is checked against
 * {@link com.cortex_clash.mind_games.auth.PasswordPolicy} in the service rather than by annotation,
 * so that registration and reset are judged by one implementation of the rules rather than two
 * copies of them.
 *
 * @param token    the raw token from the emailed link
 * @param password the new password
 */
public record ResetPassword(
        @NotBlank String token,
        @NotBlank String password
) {}
