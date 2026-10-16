package com.codelensai.controller;

import com.codelensai.config.DemoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

/**
 * Auth surface. {@code GET /api/auth/me} returns the logged-in GitHub identity from the OAuth2 session
 * (never the access token); {@code POST /api/auth/logout} invalidates the session; {@code GET
 * /api/auth/config} tells the login page which sign-in options exist (the demo login only under the
 * {@code demo} profile).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final Environment env;
    private final DemoProperties demo;

    public AuthController(Environment env, DemoProperties demo) {
        this.env = env;
        this.demo = demo;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(Map.of(
                "login", String.valueOf((Object) principal.getAttribute("login")),
                "name", String.valueOf((Object) principal.getAttribute("name")),
                "avatarUrl", String.valueOf((Object) principal.getAttribute("avatar_url"))));
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        boolean demoEnabled = Arrays.asList(env.getActiveProfiles()).contains("demo") && Boolean.TRUE.equals(demo.enabled());
        // Under the demo profile GitHub sign-in is offered only if a real OAuth app is configured;
        // otherwise the login page would show a button that can only fail.
        String clientId = env.getProperty("spring.security.oauth2.client.registration.github.client-id", "");
        boolean realClient = !clientId.isBlank() && !"placeholder-client-id".equals(clientId);
        return Map.of("demoEnabled", demoEnabled, "githubLoginEnabled", !demoEnabled || realClient);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
