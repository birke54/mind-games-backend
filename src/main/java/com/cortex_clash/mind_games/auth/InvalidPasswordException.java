package com.cortex_clash.mind_games.auth;

/**
 * Thrown when a proposed password fails {@link PasswordPolicy}.
 *
 * <p>Extends {@link IllegalArgumentException} so that registration's existing handling — audit the
 * failure, reject the request — applies to it unchanged. Controllers catch it ahead of the general
 * case to answer 400 (the request was malformed) rather than 409 (it collided with existing data).
 */
public class InvalidPasswordException extends IllegalArgumentException {

    /**
     * Constructs the exception with a message safe to return to the caller: it describes the rule
     * that was broken and never echoes the password.
     *
     * @param message the reason the password was rejected
     */
    public InvalidPasswordException(String message) {
        super(message);
    }
}
