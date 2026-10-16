package com.codelensai.demo;

import com.codelensai.service.github.GitHubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network-free GitHub for the {@code demo} profile. The demo repository's default branch is served from
 * the classpath ({@code demo/repo/**}); PR diffs come from persisted snapshots, so {@link #fetchPullDiff}
 * is never needed; summary comments are dropped and "apply suggestion" returns a fake GitHub URL.
 */
@Component
@Profile("demo")
public class DemoGitHubApiClient implements GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(DemoGitHubApiClient.class);
    private static final AtomicLong IDS = new AtomicLong(910_000);

    private final DemoContent content;

    public DemoGitHubApiClient(DemoContent content) {
        this.content = content;
    }

    @Override
    public String fetchPullDiff(String fullName, int prNumber, String token) {
        return "";
    }

    @Override
    public List<PrFile> listPullFiles(String fullName, int prNumber, String token) {
        return List.of();
    }

    @Override
    public Optional<RepoInfo> getRepo(String fullName, String token) {
        return DemoContent.REPO.equals(fullName) ? Optional.of(new RepoInfo(DemoContent.GITHUB_REPO_ID, "main")) : Optional.empty();
    }

    /** Only the default branch exists here (never a PR head: its content would not match the diff). */
    @Override
    public Optional<RepoTree> getTree(String fullName, String treeish, String token) {
        if (!DemoContent.REPO.equals(fullName) || !"main".equals(treeish)) {
            return Optional.empty();
        }
        List<RepoTree.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, String> f : content.repoFiles().entrySet()) {
            entries.add(new RepoTree.Entry(f.getKey(), "blob", DemoContent.blobSha(f.getKey()), f.getValue().length()));
        }
        return Optional.of(new RepoTree("main", false, entries));
    }

    @Override
    public Optional<String> getBlob(String fullName, String blobSha, String token) {
        return content.repoFiles().entrySet().stream()
                .filter(e -> DemoContent.blobSha(e.getKey()).equals(blobSha))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @Override
    public void postIssueComment(String fullName, int prNumber, String body, String token) {
        log.debug("[demo] would post summary to {}#{}", fullName, prNumber);
    }

    @Override
    public CreatedReviewComment createReviewComment(String fullName, int prNumber, String token, NewReviewComment comment) {
        long id = IDS.incrementAndGet();
        return new CreatedReviewComment(id, "https://github.com/" + fullName + "/pull/" + prNumber + "#discussion_r" + id);
    }

    @Override
    public Long createWebhook(String fullName, String token, String webhookUrl, String secret) {
        return null;
    }

    @Override
    public void deleteWebhook(String fullName, long hookId, String token) {
        // nothing registered in demo mode
    }
}
