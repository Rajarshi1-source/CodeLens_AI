package com.codelensai.service;

import com.codelensai.model.entity.User;
import com.codelensai.repository.UserRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Create-or-update the logged-in GitHub user and resolve the current user from the OAuth2 session. */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Upsert on login, persisting the (to-be-encrypted) access token used for GitHub API calls. */
    @Transactional
    public User upsertFromOAuth(long githubId, String login, String avatarUrl, String email, String accessToken) {
        User user = userRepository.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setUsername(login == null || login.isBlank() ? ("user-" + githubId) : login);
        user.setAvatarUrl(avatarUrl);
        user.setEmail(email);
        if (accessToken != null && !accessToken.isBlank()) {
            user.setAccessToken(accessToken);
        } else if (user.getAccessToken() == null) {
            // access_token is NOT NULL; keep a placeholder if GitHub didn't return one this round.
            user.setAccessToken("");
        }
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public Optional<User> currentUser(OAuth2User principal) {
        if (principal == null) {
            return Optional.empty();
        }
        Object id = principal.getAttribute("id");
        if (id == null) {
            return Optional.empty();
        }
        return userRepository.findByGithubId(((Number) id).longValue());
    }
}
