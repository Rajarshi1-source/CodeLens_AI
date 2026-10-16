package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Browser-facing settings ({@code codelens.web.*}). {@code allowed-origins} replaces the old
 * {@code "*"} on the STOMP endpoint: with cookie auth a wildcard invites cross-site WebSocket hijacking.
 */
@ConfigurationProperties(prefix = "codelens.web")
public record WebProperties(List<String> allowedOrigins) {
    public WebProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:5173", "http://localhost:4173", "http://localhost:3000",
                    "http://127.0.0.1:5173", "http://127.0.0.1:4173", "http://127.0.0.1:3000");
        }
    }
}
