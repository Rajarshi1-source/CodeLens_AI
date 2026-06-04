package com.codelensai.service;

import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.User;
import com.codelensai.repository.RepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Connect/disconnect a GitHub repository for the logged-in user. Connecting registers a
 * {@code pull_request} webhook on GitHub (best-effort) and stores its id + (encrypted) secret; the
 * repo is persisted locally regardless so the dashboard reflects it even without a public webhook URL.
 */
@Service
public class RepositoryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RepositoryRepository repositoryRepository;
    private final GitHubService gitHubService;
    private final String webhookPublicUrl;

    public RepositoryService(RepositoryRepository repositoryRepository,
                             GitHubService gitHubService,
                             @Value("${codelens.webhook.public-url:}") String webhookPublicUrl) {
        this.repositoryRepository = repositoryRepository;
        this.gitHubService = gitHubService;
        this.webhookPublicUrl = webhookPublicUrl;
    }

    @Transactional(readOnly = true)
    public List<Repository> listForUser(Long userId) {
        return repositoryRepository.findByUserId(userId);
    }

    @Transactional
    public Repository connect(User owner, String fullName, Long githubRepoId) {
        Repository repo = repositoryRepository.findByFullName(fullName).orElseGet(Repository::new);
        repo.setFullName(fullName);
        repo.setUserId(owner.getId());
        repo.setGithubRepoId(githubRepoId == null ? 0L : githubRepoId);
        repo.setActive(Boolean.TRUE);

        if (repo.getWebhookId() == null && webhookPublicUrl != null && !webhookPublicUrl.isBlank()) {
            String secret = newSecret();
            Long hookId = gitHubService.createWebhook(fullName, owner.getAccessToken(), webhookPublicUrl, secret);
            if (hookId != null) {
                repo.setWebhookId(hookId);
                repo.setWebhookSecret(secret); // encrypted at rest by the JPA converter
            }
        }
        return repositoryRepository.save(repo);
    }

    @Transactional
    public boolean disconnect(User owner, long repoId) {
        Optional<Repository> found = repositoryRepository.findById(repoId);
        if (found.isEmpty()) {
            return false;
        }
        Repository repo = found.get();
        if (!owner.getId().equals(repo.getUserId())) {
            return false;
        }
        repo.setActive(Boolean.FALSE);
        if (repo.getWebhookId() != null) {
            gitHubService.deleteWebhook(repo.getFullName(), repo.getWebhookId(), owner.getAccessToken());
            repo.setWebhookId(null);
        }
        repositoryRepository.save(repo);
        log.info("Disconnected repo {} for user {}", repo.getFullName(), owner.getId());
        return true;
    }

    private String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
