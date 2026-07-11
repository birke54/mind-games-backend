package com.cortex_clash.mind_games.security;

import com.cortex_clash.mind_games.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/** Issues and verifies signed JWTs used as bearer tokens for authenticated API calls. */
@Service
public class JwtService {

    /** HMAC-SHA256 signing key, derived from the Base64-decoded {@code app.jwt.secret}. */
    private final SecretKey key;

    /** Access-token lifetime in minutes ({@code app.jwt.expiration-minutes}). */
    private final long expirationMinutes;

    /**
     * @param secret            Base64-encoded HMAC-SHA256 signing secret (at least 32 bytes)
     * @param expirationMinutes access-token lifetime, in minutes
     */
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMinutes = expirationMinutes;
    }

    /**
     * Signs a token whose subject is the username, carrying the user id as a {@code uid} claim and
     * expiring {@code expirationMinutes} from now.
     *
     * @param user the authenticated user to issue a token for
     * @return the compact, signed JWT string
     */
    public String issueToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies the signature and expiration and returns the parsed claims.
     *
     * @param token the compact JWT string to verify
     * @return the verified claims
     * @throws io.jsonwebtoken.JwtException if the token is malformed, tampered, or expired
     */
    public Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
    }

    /**
     * Convenience: returns the subject (username) from a verified token.
     *
     * @param token the compact JWT string
     * @return the username carried in the token's subject
     * @throws io.jsonwebtoken.JwtException if the token is malformed, tampered, or expired
     */
    public String extractUsername(String token) {
        return parse(token).getPayload().getSubject();
    }
}
