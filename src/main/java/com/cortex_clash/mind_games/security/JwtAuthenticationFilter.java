package com.cortex_clash.mind_games.security;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.User;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads a {@code Authorization: Bearer <jwt>} header, verifies the token, and populates the
 * {@link SecurityContextHolder} so downstream authorization rules see an authenticated user.
 *
 * <p>An absent or invalid token — or a token for an account that no longer exists or has
 * since been locked — simply leaves the context unauthenticated, so the security chain
 * returns 401 for any endpoint that requires authentication. The per-request user lookup is
 * what makes a lockout take effect immediately, before the token would otherwise expire.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Prefix of the {@code Authorization} header value carrying a bearer token. */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    /**
     * @param jwtService     verifies bearer tokens and extracts their subject
     * @param userRepository looks up the user per request to honor deletion/lockout immediately
     */
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * Extracts and verifies the bearer token; on success sets an authenticated principal in the
     * {@link SecurityContextHolder}. An absent/invalid token, or one for a missing or locked-out
     * account, leaves the context unauthenticated so protected endpoints return 401. The request
     * always continues down the filter chain regardless of the outcome.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                String username = jwtService.extractUsername(token);
                User user = userRepository.findByUsername(username).orElse(null);
                // Only authenticate if the account still exists and is not locked out —
                // this revokes a still-valid token the moment the user is locked.
                if (user != null && !user.isLockedOut()) {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            username, null, AuthorityUtils.NO_AUTHORITIES);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException e) {
                // Malformed/tampered/expired token: leave the request unauthenticated.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
