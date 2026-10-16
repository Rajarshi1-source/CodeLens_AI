package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demo profile switches ({@code codelens.demo.*}). Demo login only exists under the {@code demo} Spring
 * profile AND {@code enabled=true}; {@code loopback-only} additionally restricts it to local callers.
 */
@ConfigurationProperties(prefix = "codelens.demo")
public record DemoProperties(Boolean enabled, Boolean loopbackOnly, Boolean allowWithRealOauth) {
    public DemoProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        if (loopbackOnly == null) {
            loopbackOnly = Boolean.TRUE;
        }
        if (allowWithRealOauth == null) {
            allowWithRealOauth = Boolean.FALSE;
        }
    }
}
