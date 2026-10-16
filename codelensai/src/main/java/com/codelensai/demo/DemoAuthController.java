package com.codelensai.demo;

import com.codelensai.config.DemoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * Demo-only sign-in (profile {@code demo} AND {@code codelens.demo.enabled=true}; the bean does not exist
 * otherwise, so the endpoint is a 404). It builds the same {@link OAuth2AuthenticationToken} a GitHub login
 * produces — so every controller, {@code @CurrentUser} and the STOMP interceptor work unchanged — and saves
 * it to the HTTP session. Loopback callers only unless {@code codelens.demo.loopback-only=false}.
 */
@RestController
@Profile("demo")
@ConditionalOnProperty(name = "codelens.demo.enabled", havingValue = "true")
public class DemoAuthController {

    private final DemoProperties demo;
    private final DemoDataSeeder seeder;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public DemoAuthController(DemoProperties demo, DemoDataSeeder seeder) {
        this.demo = demo;
        this.seeder = seeder;
    }

    @PostMapping("/api/auth/demo-login")
    public ResponseEntity<Map<String, Object>> login(@RequestParam(name = "as", defaultValue = "alice") String as,
                                                     HttpServletRequest request, HttpServletResponse response) {
        requireLoopback(request);
        DemoDataSeeder.DemoUser user = DemoDataSeeder.USERS.get(as);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown demo user: " + as);
        }
        Map<String, Object> attrs = Map.of(
                "id", user.githubId(),
                "login", user.login(),
                "name", user.name(),
                "avatar_url", user.avatarUrl(),
                "email", user.login() + "@demo.codelens.local");
        DefaultOAuth2User principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attrs, "login");
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");

        request.getSession(true);
        request.changeSessionId(); // session-fixation protection, as a real login would
        SecurityContext ctx = holder.createEmptyContext();
        ctx.setAuthentication(auth);
        holder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
        return ResponseEntity.ok(Map.of("login", user.login(), "avatarUrl", user.avatarUrl()));
    }

    /** Wipe and re-seed the demo data (used by the Playwright global setup). */
    @PostMapping("/api/demo/reset")
    public ResponseEntity<Map<String, Object>> reset(HttpServletRequest request) {
        requireLoopback(request);
        return ResponseEntity.ok(seeder.reset());
    }

    private void requireLoopback(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(demo.loopbackOnly())) {
            return;
        }
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "demo login is loopback-only");
            }
        } catch (java.net.UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "demo login is loopback-only");
        }
    }
}
