package com.codelensai.support;

import com.codelensai.model.entity.User;
import com.codelensai.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Test-only login (imported explicitly by WebSocket tests; never on the production classpath): puts an
 * OAuth2 principal for an existing user into a real HTTP session, so a real STOMP handshake carries it.
 * Lives under {@code /api/auth/**}, which is permitted.
 */
@RestController
public class TestLoginController {

    private final UserRepository users;

    public TestLoginController(UserRepository users) {
        this.users = users;
    }

    @PostMapping("/api/auth/test-login")
    public String login(@RequestParam long userId, HttpServletRequest request, HttpServletResponse response) {
        User u = users.findById(userId).orElseThrow();
        Map<String, Object> attrs = Map.of("id", u.getGithubId(), "login", u.getUsername(),
                "avatar_url", String.valueOf(u.getAvatarUrl()));
        DefaultOAuth2User principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attrs, "login");
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github"));
        request.getSession(true);
        new HttpSessionSecurityContextRepository().saveContext(ctx, request, response);
        return "ok";
    }
}
