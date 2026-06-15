package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse LLM-tracing settings bound from {@code codelens.langfuse.*}. Tracing is opt-in: it stays
 * off (a no-op) unless {@code enabled=true} and both keys are present, so local/CI runs need no
 * Langfuse account.
 */
@ConfigurationProperties(prefix = "codelens.langfuse")
public record LangfuseProperties(
        boolean enabled,
        String host,
        String publicKey,
        String secretKey
) {
    public LangfuseProperties {
        if (host == null || host.isBlank()) {
            host = "http://localhost:3000";
        }
    }

    /** True only when tracing is enabled and credentials are configured. */
    public boolean isUsable() {
        return enabled
                && publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
