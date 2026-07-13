package com.cortex_clash.mind_games.auth;

/**
 * Thrown when a presented password-reset token cannot be redeemed: unknown, already used, or
 * expired.
 *
 * <p>The three cases are deliberately not distinguished to the caller. "This token was already
 * used" tells whoever is holding a stolen link that it was real, and there is nothing the
 * legitimate user does differently in response to any of them — they request a fresh link either
 * way.
 */
public class InvalidResetTokenException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message the internal reason, for logs; callers receive a generic message
     */
    public InvalidResetTokenException(String message) {
        super(message);
    }
}
