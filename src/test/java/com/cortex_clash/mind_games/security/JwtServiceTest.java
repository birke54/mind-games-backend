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
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

        assertThrows(JwtException.class, () -> jwtService.parse(tampered));
    }
}
