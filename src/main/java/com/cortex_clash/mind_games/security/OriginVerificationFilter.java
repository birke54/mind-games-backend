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
 * authentication or business logic runs. It guards the application port only — actuator listens on
 * its own port, which this filter never sees, so the ALB health check and the metrics scrape reach
 * it without carrying the header.
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

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
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
