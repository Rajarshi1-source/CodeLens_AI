package com.codelensai.service.github;

import java.util.List;
import java.util.Optional;

/**
 * Thin, resilient port onto the GitHub REST API. Kept as an interface so the {@code demo} profile (and
 * tests) can swap in a network-free implementation, and so every call goes through a Spring proxy —
 * the Resilience4j annotations live on the implementation's public methods and are therefore woven
 * (calling an annotated method from inside the same bean silently bypasses the aspect).
 *
 * <p>Read methods degrade (empty / {@code ""}) rather than throw; write methods that the user asked for
 * explicitly ({@link #createReviewComment}) throw so the caller can surface the failure.
 */
public interface GitHubApiClient {

    /** Unified diff of a PR ({@code Accept: application/vnd.github.v3.diff}); {@code ""} on failure. */
    String fetchPullDiff(String fullName, int prNumber, String token);

    /** Files changed in a PR (paginated); empty on failure. */
    List<PrFile> listPullFiles(String fullName, int prNumber, String token);

    /** Repository metadata (id + default branch); empty on failure. */
    Optional<RepoInfo> getRepo(String fullName, String token);

    /** Recursive git tree for a ref or tree sha; empty on failure. */
    Optional<RepoTree> getTree(String fullName, String treeish, String token);

    /** Raw content of a blob by its git sha; empty on failure. */
    Optional<String> getBlob(String fullName, String blobSha, String token);

    /** Best-effort issue comment on a PR. Never throws. */
    void postIssueComment(String fullName, int prNumber, String body, String token);

    /** Inline PR review comment (used for "apply suggestion"). Throws on failure. */
    CreatedReviewComment createReviewComment(String fullName, int prNumber, String token, NewReviewComment comment);

    /** Register a {@code pull_request} webhook; {@code null} when it could not be created. */
    Long createWebhook(String fullName, String token, String webhookUrl, String secret);

    /** Best-effort webhook removal. Never throws. */
    void deleteWebhook(String fullName, long hookId, String token);

    record PrFile(String filename, String status, int additions, int deletions, String patch) {
    }

    record RepoInfo(long id, String defaultBranch) {
    }

    record RepoTree(String sha, boolean truncated, List<Entry> entries) {
        public record Entry(String path, String type, String sha, long size) {
            public boolean isBlob() {
                return "blob".equals(type);
            }
        }
    }

    record NewReviewComment(String body, String commitId, String path, int line, String side) {
    }

    record CreatedReviewComment(long id, String htmlUrl) {
    }

    /** Splits {@code owner/repo}; {@code null} when malformed. */
    static String[] ownerRepo(String fullName) {
        if (fullName == null) {
            return null;
        }
        String[] parts = fullName.split("/", 2);
        return parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank() ? parts : null;
    }
}
