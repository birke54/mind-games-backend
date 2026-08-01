package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.metrics.MetricName;
import com.cortex_clash.mind_games.metrics.MetricsService;
import com.cortex_clash.mind_games.metrics.TagName;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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
    private final MetricsService metricsService;

    /**
     * Minimum password length. Anything shorter is brute-forceable offline; {@code a} is currently
     * a valid password, which is what this exists to end.
     */
    public static final int MIN_LENGTH = 8;

    /**
     * Maximum password length, in bytes of UTF-8.
     *
     * <p>Set to 30 bytes because the alphanumerics and basic symbols that make up a standard
     * password are one byte each in UTF-8, so for those passwords this is effectively a
     * 30-character limit.
     *
     * <p>TODO: reject passwords containing accented characters or emoji, which encode to more than
     * one byte and so hit this ceiling well short of 30 characters.
     */
    public static final int MAX_LENGTH_BYTES = 30;

    private PasswordPolicy(UserRepository userRepository, PasswordEncoder passwordEncoder, MetricsService metricsService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.metricsService = metricsService;
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
    public static void validate(String username, String password, Boolean isPasswordReset, UserRepository userRepository, PasswordEncoder passwordEncoder, MetricsService metricsService) {
        new PasswordPolicy(userRepository, passwordEncoder, metricsService).validateInstance(username, password, isPasswordReset);
    }

    /**
     * Validates a candidate password.
     *
     * @param password the raw password
     * @throws InvalidPasswordException if the password is null, blank, too short, or too long
     */
    private void validateInstance(String username, String password, Boolean isPasswordReset) {
        if (password == null || password.isBlank()) {
            metricsService.recordCounter(MetricName.PASSWORD_POLICY_ERROR_COUNT, Map.of(TagName.REASON, PasswordPolicyFailureReason.PASSWORD_EMPTY));
            throw new InvalidPasswordException("Password must not be blank");
        }
        if (password.length() < MIN_LENGTH) {
            metricsService.recordCounter(MetricName.PASSWORD_POLICY_ERROR_COUNT, Map.of(TagName.REASON, PasswordPolicyFailureReason.PASSWORD_LENGTH));
            throw new InvalidPasswordException(
                    "Password must be at least " + MIN_LENGTH + " characters");
        }
        // Counted in bytes, not characters: a password of accented characters or emoji encodes to
        // more than one byte each, so it hits the ceiling well short of 30 characters.
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_LENGTH_BYTES) {
            metricsService.recordCounter(MetricName.PASSWORD_POLICY_ERROR_COUNT, Map.of(TagName.REASON, PasswordPolicyFailureReason.PASSWORD_LENGTH));
            throw new InvalidPasswordException(
                    "Password must be at most " + MAX_LENGTH_BYTES + " bytes");
        }
        if (isPasswordReset) {
            Optional<User> existingUser = userRepository.findByUsername(username);
            String existingPasswordHash = existingUser.map(User::getPasswordHash).orElse(null);
            if (passwordEncoder.matches(password, existingPasswordHash)) {
                metricsService.recordCounter(MetricName.PASSWORD_POLICY_ERROR_COUNT, Map.of(TagName.REASON, PasswordPolicyFailureReason.MATCHES_EXISTING));
                throw new InvalidPasswordException("New password must be different from existing password");
            }
        }
    }
}
