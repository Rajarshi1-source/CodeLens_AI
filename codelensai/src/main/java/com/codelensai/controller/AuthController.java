package com.codelensai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Auth surface. {@code GET /api/auth/me} returns the logged-in GitHub identity from the OAuth2
 * session (never the access token). Logout is handled by Spring Security's logout filter.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(Map.of(
                "login", String.valueOf(principal.getAttribute("login")),
                "name", String.valueOf(principal.getAttribute("name")),
                "avatarUrl", String.valueOf(principal.getAttribute("avatar_url"))));
    }
}
