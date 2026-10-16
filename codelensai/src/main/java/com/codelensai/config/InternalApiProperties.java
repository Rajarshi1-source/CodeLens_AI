package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guard for the eval-only {@code /api/internal/**} surface ({@code codelens.internal.*}). Disabled by
 * default (it spends LLM tokens unauthenticated); when {@code token} is set, callers must send it in
 * {@code X-Internal-Token}.
 */
@ConfigurationProperties(prefix = "codelens.internal")
public record InternalApiProperties(Boolean enabled, String token) {
    public InternalApiProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
    }
}
