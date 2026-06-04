package com.codelensai.service;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.User;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.repository.UserRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Talks to the GitHub REST API: fetch a PR's unified diff and post a review summary comment.
 *
 * <p>Auth is per-repo-owner: the bearer token is resolved from the owning {@link User} (decrypted by
 * the JPA converter). Diffs are cached in Redis keyed by {@code (prId, headSha)} for 1h to respect
 * GitHub rate limits and avoid re-fetching the same commit. All outbound calls run through the
 * Resilience4j {@code github} circuit breaker + retry; on failure we degrade to an empty diff so the
 * pipeline still completes (the PR simply gets no AI comments rather than failing hard).
 */
@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final MediaType DIFF_MEDIA_TYPE = MediaType.valueOf("application/vnd.github.v3.diff");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DIFF_CACHE_TTL = Duration.ofHours(1);

    private final WebClient githubWebClient;
    private final StringRedisTemplate redis;
    private final PullRequestRepository pullRequestRepository;
    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;

    public GitHubService(WebClient githubWebClient,
                         StringRedisTemplate redis,
                         PullRequestRepository pullRequestRepository,
                         RepositoryRepository repositoryRepository,
                         UserRepository userRepository) {
        this.githubWebClient = githubWebClient;
        this.redis = redis;
        this.pullRequestRepository = pullRequestRepository;
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
    }

    /** Fetch the PR's unified diff (Redis-cached by {@code (prId, headSha)}); empty string on any failure. */
    public String fetchPrDiff(long prId) {
        PullRequest pr = pullRequestRepository.findById(prId).orElse(null);
        if (pr == null) {
            log.warn("fetchPrDiff: no PR with id={}", prId);
            return "";
        }
        Repository repo = pr.getRepoId() == null ? null
                : repositoryRepository.findById(pr.getRepoId()).orElse(null);
        if (repo == null || repo.getFullName() == null || pr.getPrNumber() == null) {
            log.warn("fetchPrDiff: missing repo/PR metadata for prId={}", prId);
            return "";
        }

        String cacheKey = "pr:diff:" + prId + ":" + pr.getHeadSha();
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String diff = fetchDiffFromGitHub(repo.getFullName(), pr.getPrNumber(), resolveToken(repo));
        if (diff != null && !diff.isBlank()) {
            redis.opsForValue().set(cacheKey, diff, DIFF_CACHE_TTL);
            return diff;
        }
        return "";
    }

    @CircuitBreaker(name = "github", fallbackMethod = "diffFallback")
    @Retry(name = "github")
    String fetchDiffFromGitHub(String fullName, int prNumber, String token) {
        String[] ownerRepo = fullName.split("/", 2);
        if (ownerRepo.length != 2) {
            log.warn("fetchPrDiff: malformed repo full_name '{}'", fullName);
            return "";
        }
        String diff = githubWebClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", ownerRepo[0], ownerRepo[1], prNumber)
                .accept(DIFF_MEDIA_TYPE)
                .headers(h -> {
                    if (token != null && !token.isBlank()) {
                        h.setBearerAuth(token);
                    }
                })
                .retrieve()
                .bodyToMono(String.class)
                .block(HTTP_TIMEOUT);
        log.info("Fetched diff for {}#{} ({} bytes)", fullName, prNumber, diff == null ? 0 : diff.length());
        return diff == null ? "" : diff;
    }

    @SuppressWarnings("unused") // Resilience4j fallback (circuit open / retries exhausted)
    private String diffFallback(String fullName, int prNumber, String token, Throwable t) {
        log.warn("GitHub diff fetch failed for {}#{} — degrading to empty diff: {}",
                fullName, prNumber, t.getMessage());
        return "";
    }

    /** Best-effort: post the review summary as a PR issue comment. Never throws into the review pipeline. */
    public void postReviewSummary(long prId, String summary) {
        PullRequest pr = pullRequestRepository.findById(prId).orElse(null);
        if (pr == null || pr.getPrNumber() == null) {
            return;
        }
        Repository repo = pr.getRepoId() == null ? null
                : repositoryRepository.findById(pr.getRepoId()).orElse(null);
        if (repo == null || repo.getFullName() == null) {
            return;
        }
        String token = resolveToken(repo);
        if (token == null || token.isBlank()) {
            log.debug("postReviewSummary: no token for repo {}, skipping comment post", repo.getFullName());
            return;
        }
        try {
            postIssueComment(repo.getFullName(), pr.getPrNumber(), "## CodeLens AI review\n\n" + summary, token);
        } catch (Exception e) {
            log.warn("postReviewSummary failed for prId={}: {}", prId, e.getMessage());
        }
    }

    @CircuitBreaker(name = "github", fallbackMethod = "postFallback")
    @Retry(name = "github")
    void postIssueComment(String fullName, int prNumber, String body, String token) {
        String[] ownerRepo = fullName.split("/", 2);
        if (ownerRepo.length != 2) {
            return;
        }
        githubWebClient.post()
                .uri("/repos/{owner}/{repo}/issues/{number}/comments", ownerRepo[0], ownerRepo[1], prNumber)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", body))
                .retrieve()
                .toBodilessEntity()
                .block(HTTP_TIMEOUT);
        log.info("Posted review summary to {}#{}", fullName, prNumber);
    }

    @SuppressWarnings("unused") // Resilience4j fallback
    private void postFallback(String fullName, int prNumber, String body, String token, Throwable t) {
        log.warn("GitHub comment post failed for {}#{}: {}", fullName, prNumber, t.getMessage());
    }

    /**
     * Best-effort: register a {@code pull_request} webhook on the repo. Returns the GitHub hook id, or
     * {@code null} if it could not be created (no token / no public URL / API failure) — the repo is
     * still connected locally so the dashboard works; the hook can be added later.
     */
    @CircuitBreaker(name = "github", fallbackMethod = "createWebhookFallback")
    @Retry(name = "github")
    public Long createWebhook(String fullName, String token, String webhookUrl, String secret) {
        if (token == null || token.isBlank() || webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }
        String[] ownerRepo = fullName.split("/", 2);
        if (ownerRepo.length != 2) {
            return null;
        }
        Map<String, Object> config = Map.of(
                "url", webhookUrl,
                "content_type", "json",
                "secret", secret,
                "insecure_ssl", "0");
        Map<String, Object> body = Map.of(
                "name", "web",
                "active", true,
                "events", java.util.List.of("pull_request"),
                "config", config);

        @SuppressWarnings("unchecked")
        Map<String, Object> created = githubWebClient.post()
                .uri("/repos/{owner}/{repo}/hooks", ownerRepo[0], ownerRepo[1])
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(HTTP_TIMEOUT);
        if (created != null && created.get("id") instanceof Number n) {
            log.info("Created webhook {} on {}", n, fullName);
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unused") // Resilience4j fallback
    private Long createWebhookFallback(String fullName, String token, String webhookUrl, String secret, Throwable t) {
        log.warn("createWebhook failed for {}: {}", fullName, t.getMessage());
        return null;
    }

    /** Best-effort: remove a previously-created webhook. Swallows failures. */
    public void deleteWebhook(String fullName, long hookId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String[] ownerRepo = fullName.split("/", 2);
        if (ownerRepo.length != 2) {
            return;
        }
        try {
            githubWebClient.delete()
                    .uri("/repos/{owner}/{repo}/hooks/{id}", ownerRepo[0], ownerRepo[1], hookId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity()
                    .block(HTTP_TIMEOUT);
            log.info("Deleted webhook {} on {}", hookId, fullName);
        } catch (Exception e) {
            log.warn("deleteWebhook failed for {} hook {}: {}", fullName, hookId, e.getMessage());
        }
    }

    private String resolveToken(Repository repo) {
        if (repo.getUserId() == null) {
            return null;
        }
        return userRepository.findById(repo.getUserId())
                .map(User::getAccessToken)
                .orElse(null);
    }
}
