package com.cortex_clash.mind_games.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of the opaque bearer secrets this application hands out — refresh tokens
 * and password-reset tokens. Both are high-entropy random strings (not JWTs) stored only as their
 * SHA-256 hash, so a database leak yields nothing that can be presented back to us.
 *
 * <p>Shared so the two token types cannot drift apart in entropy or hashing.
 */
public final class OpaqueTokens {

    /** Number of random bytes behind each token (256 bits of entropy). */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OpaqueTokens() {
        // Static utility.
    }

    /**
     * Generates a cryptographically secure random token.
     *
     * @return a URL-safe Base64 token (unpadded) carrying 256 bits of entropy. URL-safe because a
     *         reset token has to survive a trip through a query string.
     */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the SHA-256 hash of a raw token. This is what gets persisted and what a presented
     * token is looked up by.
     *
     * @param value the raw token
     * @return hexadecimal representation of the hash
     * @throws IllegalStateException if SHA-256 is not available
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
