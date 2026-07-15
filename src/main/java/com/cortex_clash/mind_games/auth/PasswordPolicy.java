package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.User;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The one place a password is judged acceptable. Registration and password reset both go through
 * it, because a rule enforced on only one of them produces an absurdity: a password you were
 * allowed to register with but are not allowed to reset to.
 *
 * <p>The rules are length-only, deliberately. Composition rules ("one digit, one symbol") are no
 * longer recommended (NIST SP 800-63B): they shrink the search space in predictable ways and push
 * users toward {@code Password1!}. Length is what buys entropy.
 */
public final class PasswordPolicy {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Minimum password length. Anything shorter is brute-forceable offline; {@code a} is currently
     * a valid password, which is what this exists to end.
     */
    public static final int MIN_LENGTH = 10;

    /**
     * Maximum password length, in bytes of UTF-8.
     *
     * <p>This is not an arbitrary cap: BCrypt <em>silently truncates</em> its input at 72 bytes.
     * Accepting a longer password would mean quietly ignoring everything past the 72nd byte, so a
     * user who believes they set a 100-character passphrase would in fact be protected by its first
     * 72 bytes. Better to reject it than to lie about it.
     */
    public static final int MAX_LENGTH_BYTES = 72;

    private PasswordPolicy(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Static entry point for new user password validation.
     *
     * @param username       the username associated with the password of either a registration or a password reset
     * @param password       the raw password
     * @param isPasswordReset whether the password is being reset or registered
     * @param userRepository the repository to query for existing user data
     * @throws InvalidPasswordException if the password is null, blank, too short, or too long
     */
    public static void validate(String username, String password, Boolean isPasswordReset, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        new PasswordPolicy(userRepository, passwordEncoder).validateInstance(username, password, isPasswordReset);
    }

    /**
     * Validates a candidate password.
     *
     * @param password the raw password
     * @throws InvalidPasswordException if the password is null, blank, too short, or too long
     */
    private void validateInstance(String username, String password, Boolean isPasswordReset) {
        if (password == null || password.isBlank()) {
            throw new InvalidPasswordException("Password must not be blank");
        }
        if (password.length() < MIN_LENGTH) {
            throw new InvalidPasswordException(
                    "Password must be at least " + MIN_LENGTH + " characters");
        }
        // Counted in bytes, not characters, because that is the unit BCrypt truncates in: a
        // 40-character password of emoji is over the limit.
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_LENGTH_BYTES) {
            throw new InvalidPasswordException(
                    "Password must be at most " + MAX_LENGTH_BYTES + " bytes");
        }
        if (isPasswordReset) {
            Optional<User> existingUser = userRepository.findByUsername(username);
            String existingPasswordHash = existingUser.map(User::getPasswordHash).orElse(null);
            if (passwordEncoder.matches(password, existingPasswordHash)) {
                throw new InvalidPasswordException("New password must be different from existing password");
            }
        }
    }
}
