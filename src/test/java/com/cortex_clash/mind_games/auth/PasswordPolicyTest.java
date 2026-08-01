package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.metrics.MetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyTest {

    // A real registry rather than a mock: the policy only increments counters, and an in-memory
    // registry lets a test read the tag back if one ever needs to assert on it.
    private final MetricsService metricsService = new MetricsService(new SimpleMeterRegistry());

    @Test
    void aSingleCharacterPasswordIsRejected() {
        // The status quo this policy exists to end: "a" was a valid password.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "a", false, null, null, metricsService));
    }

    @Test
    void aPasswordShorterThanTheMinimumIsRejected() {
        // Seven characters: one short of the eight the policy asks for.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "sevench", false, null, null, metricsService));
    }

    @Test
    void aPasswordAtTheMinimumIsAccepted() {
        // Exactly eight. Spelled out rather than derived from MIN_LENGTH: a test that reads the
        // constant it is testing agrees with any value the constant happens to hold, including one
        // changed by accident.
        assertDoesNotThrow(() -> PasswordPolicy.validate(null, "eightchr", false, null, null, metricsService));
    }

    @Test
    void blankAndNullAreRejected() {
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, null, false, null, null, metricsService));
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null , "", false, null, null, metricsService));
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "        ", false, null, null, metricsService));
    }

    @Test
    void aPasswordAtTheMaximumIsAcceptedAndOneByteOverIsRejected() {
        // Single-byte characters, so 30 of them is exactly the 30-byte ceiling.
        assertDoesNotThrow(() -> PasswordPolicy.validate(null, "x".repeat(30), false, null, null, metricsService));
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "x".repeat(31), false, null, null, metricsService));
    }

    @Test
    void theLimitIsBytesNotCharacters() {
        // 25 four-byte emoji = 100 bytes, but only 50 chars (surrogate pairs). The ceiling counts
        // bytes, so this is over the limit even though it "looks" short enough.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "🔒".repeat(25), false, null, null, metricsService));
    }

    // ---- the reset-only rule: a new password may not be the current one ----

    @Test
    void aResetToTheCurrentPasswordIsRejected() {
        // The reason this branch exists: resetting "back" to the password you already have is a
        // no-op dressed up as a change, and defeats a reset done because the old one leaked.
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserRepository userRepository = mock(UserRepository.class);
        User alice = new User("alice", "alice@example.com", encoder.encode("current password"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertThrows(InvalidPasswordException.class,
                () -> PasswordPolicy.validate("alice", "current password", true, userRepository, encoder, metricsService));
    }

    @Test
    void aResetToADifferentPasswordIsAccepted() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserRepository userRepository = mock(UserRepository.class);
        User alice = new User("alice", "alice@example.com", encoder.encode("current password"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertDoesNotThrow(
                () -> PasswordPolicy.validate("alice", "a brand new password", true, userRepository, encoder, metricsService));
    }

    @Test
    void theSamePasswordIsFineWhenItIsNotAReset() {
        // The "different from current" check is reset-only; registration never runs it, so the same
        // string that a reset would reject must sail through here without touching the repository.
        UserRepository userRepository = mock(UserRepository.class);
        assertDoesNotThrow(
                () -> PasswordPolicy.validate("alice", "current password", false, userRepository, null, metricsService));
    }

    @Test
    void aResetForAUserThatCannotBeFoundDoesNotThrow() {
        // Defensive: the caller already resolved the user by id, but if the by-username lookup comes
        // up empty there is no hash to compare against, and matching against nothing must not NPE.
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertDoesNotThrow(
                () -> PasswordPolicy.validate("ghost", "a brand new password", true, userRepository, encoder, metricsService));
    }
}
