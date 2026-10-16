package com.codelensai.service.github;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Real GitHub REST client. Every public method is wrapped in the {@code github} circuit breaker +
 * retry; because callers reach it through the Spring proxy, those aspects actually apply.
 */
@Component
@Profile("!demo")
public class HttpGitHubApiClient implements GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpGitHubApiClient.class);
    private static final MediaType DIFF = MediaType.valueOf("application/vnd.github.v3.diff");
    private static final MediaType RAW = MediaType.valueOf("application/vnd.github.raw+json");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_FILE_PAGES = 30;

    private final WebClient githubWebClient;

    public HttpGitHubApiClient(WebClient githubWebClient) {
        this.githubWebClient = githubWebClient;
    }

    @Override
    @CircuitBreaker(name = "github", fallbackMethod = "diffFallback")
    @Retry(name = "github")
    public String fetchPullDiff(String fullName, int prNumber, String token) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null) {
            return "";
        }
        String diff = githubWebClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", or[0], or[1], prNumber)
                .accept(DIFF)
                .headers(auth(token))
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);
        log.info("Fetched diff for {}#{} ({} bytes)", fullName, prNumber, diff == null ? 0 : diff.length());
        return diff == null ? "" : diff;
    }

    @SuppressWarnings("unused")
    private String diffFallback(String fullName, int prNumber, String token, Throwable t) {
        log.warn("GitHub diff fetch failed for {}#{} — degrading to empty diff: {}", fullName, prNumber, t.getMessage());
        return "";
    }

    @Override
    @CircuitBreaker(name = "github", fallbackMethod = "filesFallback")
    @Retry(name = "github")
    public List<PrFile> listPullFiles(String fullName, int prNumber, String token) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null) {
            return List.of();
        }
        List<PrFile> files = new ArrayList<>();
        for (int page = 1; page <= MAX_FILE_PAGES; page++) {
            JsonNode arr = githubWebClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/files?per_page=100&page={page}",
                            or[0], or[1], prNumber, page)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(auth(token))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                break;
            }
            for (JsonNode f : arr) {
                files.add(new PrFile(
                        f.path("filename").asString(""),
                        f.path("status").asString(""),
                        f.path("additions").asInt(0),
                        f.path("deletions").asInt(0),
                        f.path("patch").asString(null)));
            }
            if (arr.size() < 100) {
                break;
            }
        }
        return files;
    }

    @SuppressWarnings("unused")
    private List<PrFile> filesFallback(String fullName, int prNumber, String token, Throwable t) {
        log.warn("GitHub PR files fetch failed for {}#{}: {}", fullName, prNumber, t.getMessage());
        return List.of();
    }

    @Override
    @CircuitBreaker(name = "github", fallbackMethod = "repoFallback")
    @Retry(name = "github")
    public Optional<RepoInfo> getRepo(String fullName, String token) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null) {
            return Optional.empty();
        }
        JsonNode repo = githubWebClient.get()
                .uri("/repos/{owner}/{repo}", or[0], or[1])
                .accept(MediaType.APPLICATION_JSON)
                .headers(auth(token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
        if (repo == null) {
            return Optional.empty();
        }
        return Optional.of(new RepoInfo(repo.path("id").asLong(0), repo.path("default_branch").asString("main")));
    }

    @SuppressWarnings("unused")
    private Optional<RepoInfo> repoFallback(String fullName, String token, Throwable t) {
        log.warn("GitHub repo lookup failed for {}: {}", fullName, t.getMessage());
        return Optional.empty();
    }

    @Override
    @CircuitBreaker(name = "github", fallbackMethod = "treeFallback")
    @Retry(name = "github")
    public Optional<RepoTree> getTree(String fullName, String treeish, String token) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null || treeish == null || treeish.isBlank()) {
            return Optional.empty();
        }
        JsonNode tree = githubWebClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{treeish}?recursive=1", or[0], or[1], treeish)
                .accept(MediaType.APPLICATION_JSON)
                .headers(auth(token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
        if (tree == null) {
            return Optional.empty();
        }
        List<RepoTree.Entry> entries = new ArrayList<>();
        for (JsonNode e : tree.path("tree")) {
            entries.add(new RepoTree.Entry(
                    e.path("path").asString(""),
                    e.path("type").asString(""),
                    e.path("sha").asString(""),
                    e.path("size").asLong(0)));
        }
        return Optional.of(new RepoTree(tree.path("sha").asString(""), tree.path("truncated").asBoolean(false), entries));
    }

    @SuppressWarnings("unused")
    private Optional<RepoTree> treeFallback(String fullName, String treeish, String token, Throwable t) {
        log.warn("GitHub tree fetch failed for {}@{}: {}", fullName, treeish, t.getMessage());
        return Optional.empty();
    }

    @Override
    @CircuitBreaker(name = "github", fallbackMethod = "blobFallback")
    @Retry(name = "github")
    public Optional<String> getBlob(String fullName, String blobSha, String token) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null || blobSha == null || blobSha.isBlank()) {
            return Optional.empty();
        }
        String body = githubWebClient.get()
                .uri("/repos/{owner}/{repo}/git/blobs/{sha}", or[0], or[1], blobSha)
                .accept(RAW)
                .headers(auth(token))
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);
        return Optional.ofNullable(body);
    }

    @SuppressWarnings("unused")
    private Optional<String> blobFallback(String fullName, String blobSha, String token, Throwable t) {
        log.debug("GitHub blob fetch failed for {} {}: {}", fullName, blobSha, t.getMessage());
        return Optional.empty();
    }

    @Override
    @CircuitBreaker(name = "github", fallbackMethod = "postFallback")
    @Retry(name = "github")
    public void postIssueComment(String fullName, int prNumber, String body, String token) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null || token == null || token.isBlank()) {
            return;
        }
        githubWebClient.post()
                .uri("/repos/{owner}/{repo}/issues/{number}/comments", or[0], or[1], prNumber)
                .headers(auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", body))
                .retrieve()
                .toBodilessEntity()
                .block(TIMEOUT);
        log.info("Posted review summary to {}#{}", fullName, prNumber);
    }

    @SuppressWarnings("unused")
    private void postFallback(String fullName, int prNumber, String body, String token, Throwable t) {
        log.warn("GitHub comment post failed for {}#{}: {}", fullName, prNumber, t.getMessage());
    }

    @Override
    @CircuitBreaker(name = "github")
    public CreatedReviewComment createReviewComment(String fullName, int prNumber, String token,
                                                    NewReviewComment comment) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null) {
            throw new IllegalArgumentException("malformed repository name: " + fullName);
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("no GitHub token for " + fullName);
        }
        JsonNode created = githubWebClient.post()
                .uri("/repos/{owner}/{repo}/pulls/{number}/comments", or[0], or[1], prNumber)
                .headers(auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "body", comment.body(),
                        "commit_id", comment.commitId(),
                        "path", comment.path(),
                        "line", comment.line(),
                        "side", comment.side()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
        if (created == null) {
            throw new IllegalStateException("GitHub returned no body for the review comment");
        }
        return new CreatedReviewComment(created.path("id").asLong(0), created.path("html_url").asString(""));
    }

    @Override
    @CircuitBreaker(name = "github", fallbackMethod = "createWebhookFallback")
    @Retry(name = "github")
    public Long createWebhook(String fullName, String token, String webhookUrl, String secret) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null || token == null || token.isBlank() || webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }
        Map<String, Object> body = Map.of(
                "name", "web",
                "active", true,
                "events", List.of("pull_request"),
                "config", Map.of("url", webhookUrl, "content_type", "json", "secret", secret, "insecure_ssl", "0"));
        JsonNode created = githubWebClient.post()
                .uri("/repos/{owner}/{repo}/hooks", or[0], or[1])
                .headers(auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
        if (created != null && created.path("id").isNumber()) {
            log.info("Created webhook {} on {}", created.path("id").asLong(), fullName);
            return created.path("id").asLong();
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Long createWebhookFallback(String fullName, String token, String webhookUrl, String secret, Throwable t) {
        log.warn("createWebhook failed for {}: {}", fullName, t.getMessage());
        return null;
    }

    @Override
    public void deleteWebhook(String fullName, long hookId, String token) {
        String[] or = GitHubApiClient.ownerRepo(fullName);
        if (or == null || token == null || token.isBlank()) {
            return;
        }
        try {
            githubWebClient.delete()
                    .uri("/repos/{owner}/{repo}/hooks/{id}", or[0], or[1], hookId)
                    .headers(auth(token))
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
            log.info("Deleted webhook {} on {}", hookId, fullName);
        } catch (Exception e) {
            log.warn("deleteWebhook failed for {} hook {}: {}", fullName, hookId, e.getMessage());
        }
    }

    private static Consumer<HttpHeaders> auth(String token) {
        return h -> {
            if (token != null && !token.isBlank()) {
                h.setBearerAuth(token);
            }
        };
    }
}
