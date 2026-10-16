package com.codelensai.service;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.User;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.repository.UserRepository;
import com.codelensai.service.github.GitHubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Domain-level GitHub operations: resolve a PR's repository and the repo owner's token, then delegate the
 * HTTP call to {@link GitHubApiClient}. Every network call now crosses a Spring proxy, so the
 * {@code github} circuit breaker and retry on the client actually apply (they used to be on package-private
 * methods called from inside this class, which bypassed the aspect).
 *
 * <p>Auth is per-repo-owner: the bearer token is the owning {@link User}'s (decrypted by the JPA converter).
 * Diff snapshots (what a review ran against) are persisted by {@code DiffSnapshotService}.
 */
@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    private final GitHubApiClient api;
    private final PullRequestRepository pullRequestRepository;
    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;

    public GitHubService(GitHubApiClient api,
                         PullRequestRepository pullRequestRepository,
                         RepositoryRepository repositoryRepository,
                         UserRepository userRepository) {
        this.api = api;
        this.pullRequestRepository = pullRequestRepository;
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
    }

    /** The PR's unified diff straight from GitHub; empty string on any failure / missing metadata. */
    public String fetchPrDiff(PullRequest pr) {
        Optional<Repository> repo = repoOf(pr);
        if (repo.isEmpty() || pr.getPrNumber() == null) {
            log.warn("fetchPrDiff: missing repo/PR metadata for prId={}", pr.getId());
            return "";
        }
        return api.fetchPullDiff(repo.get().getFullName(), pr.getPrNumber(), tokenFor(repo.get()));
    }

    public List<GitHubApiClient.PrFile> listPullFiles(PullRequest pr) {
        return repoOf(pr)
                .map(r -> api.listPullFiles(r.getFullName(), pr.getPrNumber(), tokenFor(r)))
                .orElse(List.of());
    }

    /** Best-effort: post the review summary as a PR issue comment. Never throws into the review pipeline. */
    public void postReviewSummary(long prId, String markdown) {
        PullRequest pr = pullRequestRepository.findById(prId).orElse(null);
        if (pr == null || pr.getPrNumber() == null) {
            return;
        }
        Optional<Repository> repo = repoOf(pr);
        if (repo.isEmpty()) {
            return;
        }
        String token = tokenFor(repo.get());
        if (token == null || token.isBlank()) {
            log.debug("postReviewSummary: no token for repo {}, skipping comment post", repo.get().getFullName());
            return;
        }
        try {
            api.postIssueComment(repo.get().getFullName(), pr.getPrNumber(), markdown, token);
        } catch (Exception e) {
            log.warn("postReviewSummary failed for prId={}: {}", prId, e.getMessage());
        }
    }

    public Long createWebhook(String fullName, String token, String webhookUrl, String secret) {
        return api.createWebhook(fullName, token, webhookUrl, secret);
    }

    public void deleteWebhook(String fullName, long hookId, String token) {
        api.deleteWebhook(fullName, hookId, token);
    }

    public Optional<GitHubApiClient.RepoInfo> repoInfo(String fullName, String token) {
        return api.getRepo(fullName, token);
    }

    public Optional<Repository> repoOf(PullRequest pr) {
        if (pr == null || pr.getRepoId() == null) {
            return Optional.empty();
        }
        return repositoryRepository.findById(pr.getRepoId()).filter(r -> r.getFullName() != null);
    }

    /** The repository owner's GitHub token (null when unknown / never stored). */
    public String tokenFor(Repository repo) {
        if (repo == null || repo.getUserId() == null) {
            return null;
        }
        return userRepository.findById(repo.getUserId())
                .map(User::getAccessToken)
                .orElse(null);
    }
}
