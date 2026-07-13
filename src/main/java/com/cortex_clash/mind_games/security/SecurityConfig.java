package com.cortex_clash.mind_games.security;

import com.cortex_clash.mind_games.auth.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless (token-only) security. Public endpoints are the ones that mint or precede a token
 * (ping, register, authenticate); everything else requires a valid bearer JWT.
 */
@Configuration
public class SecurityConfig {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public SecurityConfig(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * Builds the security filter chain: CSRF is disabled (safe for a stateless bearer-token API),
     * sessions are stateless, the health probes and token-minting/pre-token endpoints are public,
     * every other request requires authentication, and the {@link JwtAuthenticationFilter} runs
     * before the username/password filter to authenticate from the bearer token.
     *
     * @param http the Spring Security builder
     * @return the configured filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                // Health probes for the load balancer / orchestrator.
                                "/actuator/health",
                                "/actuator/health/**",
                                // Spring ERROR-dispatches thrown exceptions here; without this the
                                // filter chain denies the dispatch and every error becomes a blank 403.
                                "/error",
                                "/api/ping",
                                "/api/v1/register",
                                "/api/v1/authenticate_user",
                                "/api/v1/refresh",
                                "/api/v1/logout",
                                // Password reset: a user who needs these cannot authenticate, by
                                // definition. The reset token is the credential.
                                "/api/v1/request_password_reset",
                                "/api/v1/reset_password").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userRepository),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
