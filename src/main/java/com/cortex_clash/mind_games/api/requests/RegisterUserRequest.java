package com.cortex_clash.mind_games.api.requests;


import com.cortex_clash.mind_games.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for user registration.
 *
 * <p>This record encapsulates the data required to register a new user in the system.
 * All fields are validated using Jakarta Bean Validation annotations to ensure data integrity.
 *
 * @param username the desired username; must not be blank and cannot exceed {@link User#USERNAME_MAX_LENGTH} characters
 * @param password the user's password; must not be blank
 * @param email    the user's email address; must not be blank, cannot exceed {@link User#EMAIL_MAX_LENGTH} characters,
 *                 and must match a valid email format (local-part@domain.tld)
 */
public record RegisterUserRequest(
        @NotBlank @Size(max = User.USERNAME_MAX_LENGTH) String username,
        @NotBlank String password,
        @NotBlank @Size(max = User.EMAIL_MAX_LENGTH) @Email(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$\n") String email
) {}