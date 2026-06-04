package com.codelensai.config;

import com.codelensai.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * On successful GitHub OAuth2 login, upsert the {@code users} row (github id, login, avatar) and store
 * the access token (encrypted at rest by the JPA converter) so repo/PR API calls can resolve an owner
 * token. Then delegate to the default redirect.
 */
@Component
public class GitHubOAuth2SuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuth2SuccessHandler.class);

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final UserService userService;

    public GitHubOAuth2SuccessHandler(OAuth2AuthorizedClientService authorizedClientService,
                                      UserService userService) {
        this.authorizedClientService = authorizedClientService;
        this.userService = userService;
        setDefaultTargetUrl("/");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, jakarta.servlet.ServletException {
        try {
            if (authentication instanceof OAuth2AuthenticationToken token
                    && token.getPrincipal() instanceof OAuth2User principal) {
                String accessToken = resolveAccessToken(token);
                Object id = principal.getAttribute("id");
                if (id != null) {
                    userService.upsertFromOAuth(
                            ((Number) id).longValue(),
                            principal.getAttribute("login"),
                            principal.getAttribute("avatar_url"),
                            principal.getAttribute("email"),
                            accessToken);
                }
            }
        } catch (Exception e) {
            // Never block login on a persistence hiccup; the session still authenticates.
            log.warn("Failed to upsert user on OAuth2 login: {}", e.getMessage());
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private String resolveAccessToken(OAuth2AuthenticationToken token) {
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                token.getAuthorizedClientRegistrationId(), token.getName());
        return client == null || client.getAccessToken() == null
                ? null : client.getAccessToken().getTokenValue();
    }
}
