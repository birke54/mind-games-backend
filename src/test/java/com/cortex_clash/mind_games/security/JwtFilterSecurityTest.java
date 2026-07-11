package com.cortex_clash.mind_games.security;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserStatus;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link JwtAuthenticationFilter} directly. Whether the SecurityContext ends up
 * authenticated is exactly what decides 200 vs. 401 downstream: an unauthenticated context
 * on a protected endpoint yields 401. This verifies the lockout-aware revocation — a valid
 * token for a since-locked account must not authenticate the request.
 *
 * TODO: Add a full @WebMvcTest slice that drives requests through the real SecurityConfig
 * filter chain and asserts the actual HTTP status (200 for active, 401 for locked/missing
 * token) end-to-end. Requires the Spring Boot 4 web test module, which isn't yet on the
 * classpath:  testImplementation 'org.springframework.boot:spring-boot-webmvc-test'
 */
class JwtFilterSecurityTest {

    // Base64-encoded 32-byte (256-bit) HMAC key, matching what the app expects.
    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final JwtService jwtService = new JwtService(SECRET, 60);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeUserWithValidTokenIsAuthenticated() throws Exception {
        User user = new User("alice", "alice@example.com", "hash");
        user.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        MockHttpServletRequest request = requestWithToken(jwtService.issueToken(user));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "active user should be authenticated");
        assertEquals("alice", auth.getName());
        verify(chain).doFilter(request, response);
    }

    @Test
    void lockedUserWithValidTokenIsNotAuthenticated_leadingTo401() throws Exception {
        User user = new User("bob", "bob@example.com", "hash");
        String token = jwtService.issueToken(user); // minted while the account was active
        user.setStatus(UserStatus.LOCKED_OUT);       // account then locked out
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));

        MockHttpServletRequest request = requestWithToken(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "locked user must not be authenticated (protected endpoints then return 401)");
        // Filter is non-terminating: the request proceeds unauthenticated and is rejected downstream.
        verify(chain).doFilter(request, response);
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
