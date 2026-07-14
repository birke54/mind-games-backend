package com.cortex_clash.mind_games.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {

    @Test
    void aSingleCharacterPasswordIsRejected() {
        // The status quo this policy exists to end: "a" was a valid password.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate("a"));
    }

    @Test
    void aPasswordShorterThanTheMinimumIsRejected() {
        // Nine characters: one short of the ten the policy asks for.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate("ninechars"));
    }

    @Test
    void aPasswordAtTheMinimumIsAccepted() {
        // Exactly ten. Spelled out rather than derived from MIN_LENGTH: a test that reads the
        // constant it is testing agrees with any value the constant happens to hold, including one
        // changed by accident.
        assertDoesNotThrow(() -> PasswordPolicy.validate("tenletters"));
    }

    @Test
    void blankAndNullAreRejected() {
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(null));
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate(""));
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate("        "));
    }

    @Test
    void aLongPassphraseIsAcceptedUpToBcryptsTruncationPoint() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("x".repeat(72)));
        // Past 72 bytes BCrypt silently ignores the rest, so we reject rather than quietly protect
        // the user with only the first 72 bytes of what they typed.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate("x".repeat(73)));
    }

    @Test
    void theLimitIsBytesNotCharacters() {
        // 25 four-byte emoji = 100 bytes, but only 50 chars (surrogate pairs). BCrypt counts bytes,
        // so this is over the limit even though it "looks" short enough.
        assertThrows(InvalidPasswordException.class, () -> PasswordPolicy.validate("🔒".repeat(25)));
    }
}
