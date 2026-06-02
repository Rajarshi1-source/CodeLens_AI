package com.codelensai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * GitHub OAuth2 login + a mostly-open public surface for infrastructure endpoints.
 *
 * <p>The webhook is {@code permitAll} because it is authenticated by HMAC signature, not by a
 * session. {@code /ws/**} and actuator health/metrics are public. Everything else requires login.
 *
 * <p>Deviation note (MVP, backend-first): the master plan's stateless + JWT-resource-server design
 * needs a JWT issuer/decoder we don't mint yet. Until the frontend + JWT minting land we rely on the
 * OAuth2 login session, so session management is left at its default (IF_REQUIRED) — STATELESS would
 * break the OAuth2 authorization-code redirect. The {@code oauth2ResourceServer().jwt()} wiring is
 * intentionally omitted to avoid a missing-{@code JwtDecoder} startup failure.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/webhooks/**").permitAll()   // verified by HMAC, not auth
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/internal/**").permitAll()   // internal eval-only endpoint
                        .anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .logout(Customizer.withDefaults())
                .build();
    }
}
