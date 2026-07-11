package com.cortex_clash.mind_games.api.requests;

import com.cortex_clash.mind_games.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for user authentication endpoint.
 *
 * <p>Carries credentials to authenticate an existing user and obtain access/refresh tokens.
 * Validated via Jakarta Bean Validation constraints.
 *
 * @param username the user's unique username; must not be blank and cannot exceed {@link User#USERNAME_MAX_LENGTH}
 * @param password the user's plaintext password; must not be blank
 */
public record AuthenticateUser(
        @NotBlank @Size(max = User.USERNAME_MAX_LENGTH) String username,
        @NotBlank String password
) {}
