package com.cortex_clash.mind_games.api.requests;


import com.cortex_clash.mind_games.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;

/**
 * Request payload for user registration.
 *
 * <p>This record encapsulates the data required to register a new user in the system.
 * All fields are validated using Jakarta Bean Validation annotations to ensure data integrity.
 *
 * @param username the desired username; must not be blank and cannot exceed {@link User#USERNAME_MAX_LENGTH} characters
 * @param password the user's password; must not be blank and cannot exceed {@link #PASSWORD_MAX_LENGTH} characters
 * @param email    the user's email address; must not be blank, cannot exceed {@link User#EMAIL_MAX_LENGTH} characters,
 *                 and must match a valid email format
 */
public record RegisterUserRequest(
        @NotBlank
        @Size(max = User.USERNAME_MAX_LENGTH)
        String username,

        @NotBlank
        @Size(max = PASSWORD_MAX_LENGTH)
        String password,

        @NotBlank
        @Size(max = User.EMAIL_MAX_LENGTH)
        @Email(regexp = EMAIL_PATTERN)
        String email
) {
    private static final int PASSWORD_MAX_LENGTH = 30;
    private static final String EMAIL_PATTERN = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
}