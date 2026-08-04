package com.cortex_clash.mind_games.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Exercises {@link OriginVerificationFilter} directly. This filter is the only thing keeping the
 * publicly reachable execute-api URL from bypassing CloudFront and WAF, and both ways it can fail
 * are quiet: letting an unverified request through reopens the bypass, and rejecting a request that
 * should pass locks out real traffic. Neither shows up as a compile error.
 *
 * <p>Requests go through {@code doFilter} rather than {@code doFilterInternal} so that
 * {@code shouldNotFilter} — which is what the blank-secret kill switch hangs off — is part of what
 * gets tested.
 */
class OriginVerificationFilterTest {

    private static final String SECRET = "s3cr3t-origin-verify-value";
    private static final String HEADER = "X-Origin-Verify";

    private final OriginVerificationFilter filter = new OriginVerificationFilter(SECRET);

    @Test
    void requestCarryingTheSecretIsPassedDownTheChain() throws Exception {
        MockHttpServletRequest request = get("/api/ping");
        request.addHeader(HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    void requestWithoutTheHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = get("/api/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test
    void requestWithTheWrongSecretIsRejected() throws Exception {
        MockHttpServletRequest request = get("/api/ping");
        request.addHeader(HEADER, "not-the-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verifyNoInteractions(chain);
    }

    /** A prefix must not pass: comparing only as far as the shorter value would accept it. */
    @Test
    void requestWithAPrefixOfTheSecretIsRejected() throws Exception {
        MockHttpServletRequest request = get("/api/ping");
        request.addHeader(HEADER, SECRET.substring(0, SECRET.length() - 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verifyNoInteractions(chain);
    }

    /**
     * Actuator used to be exempt, back when it answered on the application port and an exempt health
     * check was the only way ECS could keep a task alive. It has its own port now, which this filter
     * never sees, so an /actuator path arriving here came in over the application port and gets no
     * special treatment.
     */
    @Test
    void actuatorPathOnTheApplicationPortIsNotExempt() throws Exception {
        MockHttpServletRequest request = get("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verifyNoInteractions(chain);
    }

    /** Blank secret is the local-development and pre-CloudFront case: the check is off entirely. */
    @Test
    void blankSecretDisablesTheCheck() throws Exception {
        OriginVerificationFilter disabled = new OriginVerificationFilter("");
        MockHttpServletRequest request = get("/api/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        disabled.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
