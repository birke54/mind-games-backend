package com.cortex_clash.mind_games.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rejects requests that did not come through CloudFront.
 *
 * <p>The execute-api URL is reachable from the internet, so a client that knows it could otherwise
 * skip CloudFront — and with it WAF and the rate limits. CloudFront is configured to inject a shared
 * secret as {@code X-Origin-Verify} on every origin request; this filter drops anything arriving
 * without it. The header is added by CloudFront itself, not the viewer, and CloudFront strips any
 * same-named header the viewer sends, so it cannot be forged from outside.
 *
 * <p>Runs ahead of the Spring Security chain: an unverified request is refused before any
 * authentication or business logic runs. The ALB's health check talks to the container directly and
 * never traverses CloudFront, so {@code /actuator/**} is exempt — without that exemption every task
 * would fail its health check and ECS would kill it in a loop.
 *
 * <p>Disabled when {@code app.security.origin-verify-secret} is blank, which is the case for local
 * development and lets the filter be deployed before CloudFront exists.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OriginVerificationFilter extends OncePerRequestFilter {

    /** Header CloudFront adds to every origin request; absent on direct hits to execute-api. */
    private static final String ORIGIN_VERIFY_HEADER = "X-Origin-Verify";

    private final byte[] expectedSecret;
    private final boolean enabled;

    /**
     * @param secret shared secret CloudFront sends as {@code X-Origin-Verify}; blank disables the filter
     */
    public OriginVerificationFilter(
            @Value("${app.security.origin-verify-secret:}") String secret) {
        this.expectedSecret = secret.getBytes(StandardCharsets.UTF_8);
        this.enabled = !secret.isBlank();
    }

    /**
     * Health probes arrive straight from the ALB, bypassing CloudFront, so they carry no header.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(ORIGIN_VERIFY_HEADER);
        if (presented == null
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedSecret)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Direct origin access is not allowed");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
