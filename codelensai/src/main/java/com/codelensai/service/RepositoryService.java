package com.codelensai.service;

import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.RepositoryMember;
import com.codelensai.model.entity.User;
import com.codelensai.repository.RepositoryMemberRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.service.index.IndexQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Connect/disconnect a GitHub repository for the logged-in user. Connecting registers a
 * {@code pull_request} webhook on GitHub (best-effort), stores its id + (encrypted) per-repo secret, adds the
 * user as a member and queues a full G1 index of the default branch.
 *
 * <p>Ownership is never re-assigned: connecting a repository someone else already connected adds the caller
 * as a {@code MEMBER} — and only if GitHub confirms the caller can read it with their own token.
 */
@Service
public class RepositoryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RepositoryRepository repositoryRepository;
    private final RepositoryMemberRepository members;
    private final GitHubService gitHubService;
    private final IndexQueueService indexQueue;
    private final String webhookPublicUrl;

    public RepositoryService(RepositoryRepository repositoryRepository,
                             RepositoryMemberRepository members,
                             GitHubService gitHubService,
                             IndexQueueService indexQueue,
                             @Value("${codelens.webhook.public-url:}") String webhookPublicUrl) {
        this.repositoryRepository = repositoryRepository;
        this.members = members;
        this.gitHubService = gitHubService;
        this.indexQueue = indexQueue;
        this.webhookPublicUrl = webhookPublicUrl;
    }

    /** Repositories the user is a member of. */
    @Transactional(readOnly = true)
    public List<Repository> listForUser(Long userId) {
        List<Long> ids = members.findByUserId(userId).stream().map(RepositoryMember::getRepoId).toList();
        return repositoryRepository.findAllById(ids);
    }

    @Transactional
    public Repository connect(User user, String fullName, Long githubRepoId) {
        Optional<Repository> existing = repositoryRepository.findByFullName(fullName);
        boolean hasToken = user.getAccessToken() != null && !user.getAccessToken().isBlank();
        if (existing.isPresent() && existing.get().getUserId() != null && !existing.get().getUserId().equals(user.getId())) {
            Repository repo = existing.get();
            if (!members.existsByRepoIdAndUserId(repo.getId(), user.getId())) {
                if (!hasToken || gitHubService.repoInfo(fullName, user.getAccessToken()).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "repository is connected by another user and GitHub did not confirm your access");
                }
                members.save(new RepositoryMember(repo.getId(), user.getId(), RepositoryMember.MEMBER));
            }
            return repo;
        }
        if (hasToken && existing.isEmpty() && gitHubService.repoInfo(fullName, user.getAccessToken()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitHub did not confirm access to " + fullName);
        }

        Repository repo = existing.orElseGet(Repository::new);
        repo.setFullName(fullName);
        repo.setUserId(user.getId());
        if (repo.getGithubRepoId() == null || repo.getGithubRepoId() == 0L) {
            repo.setGithubRepoId(githubRepoId == null ? 0L : githubRepoId);
        }
        repo.setActive(Boolean.TRUE);
        if (repo.getWebhookId() == null && webhookPublicUrl != null && !webhookPublicUrl.isBlank()) {
            String secret = newSecret();
            Long hookId = gitHubService.createWebhook(fullName, user.getAccessToken(), webhookPublicUrl, secret);
            if (hookId != null) {
                repo.setWebhookId(hookId);
                repo.setWebhookSecret(secret); // encrypted at rest by the JPA converter
            }
        }
        Repository saved = repositoryRepository.save(repo);
        if (!members.existsByRepoIdAndUserId(saved.getId(), user.getId())) {
            members.save(new RepositoryMember(saved.getId(), user.getId(), RepositoryMember.OWNER));
        }
        if (hasToken) {
            indexQueue.enqueueFull(saved.getId());
        }
        return saved;
    }

    /** Queue a full re-index (members only). */
    @Transactional(readOnly = true)
    public boolean reindex(User user, long repoId) {
        if (!members.existsByRepoIdAndUserId(repoId, user.getId())) {
            return false;
        }
        indexQueue.enqueueFull(repoId);
        return true;
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
