package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyTest {

    @Test
    void aSingleCharacterPasswordIsRejected() {
        // The status quo this policy exists to end: "a" was a valid password.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "a", false, null, null));
    }

    @Test
    void aPasswordShorterThanTheMinimumIsRejected() {
        // Nine characters: one short of the ten the policy asks for.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "ninechars", false, null, null));
    }

    @Test
    void aPasswordAtTheMinimumIsAccepted() {
        // Exactly ten. Spelled out rather than derived from MIN_LENGTH: a test that reads the
        // constant it is testing agrees with any value the constant happens to hold, including one
        // changed by accident.
        assertDoesNotThrow(() -> PasswordPolicy.validate(null, "tenletters", false, null, null));
    }

    @Test
    void blankAndNullAreRejected() {
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, null, false, null, null));
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null , "", false, null, null));
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "        ", false, null, null));
    }

    @Test
    void aLongPassphraseIsAcceptedUpToBcryptsTruncationPoint() {
        assertDoesNotThrow(() -> PasswordPolicy.validate(null, "x".repeat(72), false, null, null));
        // Past 72 bytes BCrypt silently ignores the rest, so we reject rather than quietly protect
        // the user with only the first 72 bytes of what they typed.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "x".repeat(73), false, null, null));
    }

    @Test
    void theLimitIsBytesNotCharacters() {
        // 25 four-byte emoji = 100 bytes, but only 50 chars (surrogate pairs). BCrypt counts bytes,
        // so this is over the limit even though it "looks" short enough.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null, "🔒".repeat(25), false, null, null));
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
                () -> PasswordPolicy.validate("alice", "current password", true, userRepository, encoder));
    }

    @Test
    void aResetToADifferentPasswordIsAccepted() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserRepository userRepository = mock(UserRepository.class);
        User alice = new User("alice", "alice@example.com", encoder.encode("current password"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertDoesNotThrow(
                () -> PasswordPolicy.validate("alice", "a brand new password", true, userRepository, encoder));
    }

    @Test
    void theSamePasswordIsFineWhenItIsNotAReset() {
        // The "different from current" check is reset-only; registration never runs it, so the same
        // string that a reset would reject must sail through here without touching the repository.
        UserRepository userRepository = mock(UserRepository.class);
        assertDoesNotThrow(
                () -> PasswordPolicy.validate("alice", "current password", false, userRepository, null));
    }

    @Test
    void aResetForAUserThatCannotBeFoundDoesNotThrow() {
        // Defensive: the caller already resolved the user by id, but if the by-username lookup comes
        // up empty there is no hash to compare against, and matching against nothing must not NPE.
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertDoesNotThrow(
                () -> PasswordPolicy.validate("ghost", "a brand new password", true, userRepository, encoder));
    }
}
