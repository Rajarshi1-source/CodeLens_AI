package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** G5 collaborative presence ({@code codelens.presence.*}): a viewer silent for {@code ttl-seconds} is pruned. */
@ConfigurationProperties(prefix = "codelens.presence")
public record PresenceProperties(Integer ttlSeconds) {
    public PresenceProperties {
        if (ttlSeconds == null || ttlSeconds <= 0) {
            ttlSeconds = 45;
        }
    }
}
