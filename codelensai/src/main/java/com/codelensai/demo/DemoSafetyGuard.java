package com.codelensai.demo;

import com.codelensai.config.DemoProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Fail-fast guard for the {@code demo} profile, which adds a password-less login. Startup aborts when the
 * demo profile is active but {@code codelens.demo.enabled} is not set, when it is combined with
 * {@code prod}, or when real GitHub OAuth credentials are configured (a real deployment) without the
 * explicit {@code codelens.demo.allow-with-real-oauth=true} override.
 */
@Configuration
@Profile("demo")
public class DemoSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(DemoSafetyGuard.class);
    static final String PLACEHOLDER_CLIENT_ID = "placeholder-client-id";

    private final Environment env;
    private final DemoProperties demo;

    public DemoSafetyGuard(Environment env, DemoProperties demo) {
        this.env = env;
        this.demo = demo;
    }

    @PostConstruct
    void check() {
        if (!Boolean.TRUE.equals(demo.enabled())) {
            throw new IllegalStateException("The 'demo' profile is active but codelens.demo.enabled is not true");
        }
        if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("The 'demo' profile must never run together with 'prod'");
        }
        String clientId = env.getProperty("spring.security.oauth2.client.registration.github.client-id", PLACEHOLDER_CLIENT_ID);
        if (!PLACEHOLDER_CLIENT_ID.equals(clientId) && !Boolean.TRUE.equals(demo.allowWithRealOauth())) {
            throw new IllegalStateException("Real GitHub OAuth credentials are configured; refusing to enable the demo "
                    + "login. Set codelens.demo.allow-with-real-oauth=true if this is intentional.");
        }
        log.warn("""

                ************************************************************************
                *  DEMO MODE: password-less demo login is ENABLED (loopback-only={}).   *
                *  Seeded data, local LLM, no GitHub calls. Never expose this publicly.  *
                ************************************************************************""", demo.loopbackOnly());
    }
}
