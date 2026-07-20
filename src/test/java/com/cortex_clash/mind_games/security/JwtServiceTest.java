package com.cortex_clash.mind_games.security;

import com.cortex_clash.mind_games.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    // Base64-encoded 32-byte (256-bit) key, as the app expects.
    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final JwtService jwtService = new JwtService(SECRET, 60);

    @Test
    void issuesTokenThatParsesBackToUsername() {
        String token = jwtService.issueToken(new User("alice", "alice@example.com", "hash"));

        assertNotNull(token);
        Jws<Claims> parsed = jwtService.parse(token);
        assertEquals("alice", parsed.getPayload().getSubject());
        assertEquals("alice", jwtService.extractUsername(token));
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwtService.issueToken(new User("bob", "bob@example.com", "hash"));

        // Tamper with the *first* character of the signature, not the last. A 32-byte HMAC encodes
        // to 43 base64url characters, of which the last carries only 4 significant bits — so
        // several different final characters decode to the very same signature, and swapping one
        // for another leaves a token that still verifies. Doing that made this test pass only
        // about 93% of the time. Every bit of the first character is significant.
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'a' ? 'b' : 'a') + parts[2].substring(1);
        String tampered = String.join(".", parts);

        assertThrows(JwtException.class, () -> jwtService.parse(tampered));
    }
}
