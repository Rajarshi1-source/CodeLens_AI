package com.codelensai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * GitHub OAuth2 login + a mostly-open public surface for infrastructure endpoints.
 *
 * <p>The webhook is {@code permitAll} because it is authenticated by HMAC signature, not by a
 * session. {@code /ws/**} is permitted at the HTTP layer but every STOMP frame is authenticated and
 * authorized by {@code StompAuthChannelInterceptor}. {@code /api/internal/**} is guarded by
 * {@code InternalApiGuardFilter}. Everything else requires login, and per-resource access is checked by
 * repository membership ({@code AccessGuard}). The {@code demo} profile adds a password-less login that
 * produces the same {@code OAuth2AuthenticationToken} (ADR-0006).
 *
 * <p>Deviation note (ADR-0004, amended by ADR-0006): the master plan's stateless + JWT-resource-server
 * design needs a JWT issuer/decoder we don't mint. We rely on the OAuth2 login session, so session
 * management is left at its default (IF_REQUIRED) — STATELESS would break the OAuth2 authorization-code
 * redirect. The {@code oauth2ResourceServer().jwt()} wiring is
 * intentionally omitted to avoid a missing-{@code JwtDecoder} startup failure.
 *
 * <p>SPA support: unauthenticated calls to {@code /api/**} return {@code 401} (so the React SPA's
 * fetch/Zod layer can react cleanly) instead of the default {@code 302} redirect to GitHub; top-level
 * browser navigation still redirects into the OAuth2 login flow.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GitHubOAuth2SuccessHandler oauth2SuccessHandler;

    public SecurityConfig(GitHubOAuth2SuccessHandler oauth2SuccessHandler) {
        this.oauth2SuccessHandler = oauth2SuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/webhooks/**").permitAll()   // verified by HMAC, not auth
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/internal/**").permitAll()   // guarded by InternalApiGuardFilter
                        .requestMatchers("/api/demo/**").permitAll()       // exists only under the demo profile
                        .requestMatchers("/api/repos/**", "/api/prs/**", "/api/reviews/**", "/api/dashboard/**",
                                "/api/comments/**")
                        .authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .oauth2Login(oauth2 -> oauth2.successHandler(oauth2SuccessHandler))
                .logout(Customizer.withDefaults())
                .build();
    }
}
