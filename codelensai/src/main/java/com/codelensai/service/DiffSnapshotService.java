package com.codelensai.service;

import com.codelensai.model.entity.PullRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Persists the exact diff each review ran against ({@code pr_diffs}, keyed by {@code (pr_id, head_sha)}).
 * The diff view then keeps working after the old 1h Redis cache would have expired, when the owner's
 * token is gone, and in the demo profile (which seeds snapshots instead of calling GitHub). Lookup order:
 * snapshot → GitHub (and store) → empty string (the UI falls back to the findings list).
 */
@Service
public class DiffSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DiffSnapshotService.class);
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final GitHubService gitHubService;

    public DiffSnapshotService(JdbcClient jdbc, GitHubService gitHubService) {
        this.jdbc = jdbc;
        this.gitHubService = gitHubService;
    }

    public String getOrFetch(PullRequest pr) {
        if (pr == null) {
            return "";
        }
        String headSha = pr.getHeadSha() == null ? "" : pr.getHeadSha();
        Optional<String> stored = find(pr.getId(), headSha);
        if (stored.isPresent()) {
            return stored.get();
        }
        String diff = gitHubService.fetchPrDiff(pr);
        if (diff != null && !diff.isBlank()) {
            store(pr.getId(), headSha, diff, "github");
            return diff;
        }
        return "";
    }

    public Optional<String> find(long prId, String headSha) {
        return jdbc.sql("SELECT diff FROM pr_diffs WHERE pr_id = :p AND head_sha = :s")
                .param("p", prId).param("s", headSha == null ? "" : headSha)
                .query(String.class).optional();
    }

    public void store(long prId, String headSha, String diff, String source) {
        byte[] bytes = diff.getBytes(StandardCharsets.UTF_8);
        String stored = diff;
        String src = source;
        if (bytes.length > MAX_BYTES) {
            stored = new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8);
            src = source + "-truncated";
            log.warn("Diff for prId={} is {} bytes; storing the first {}", prId, bytes.length, MAX_BYTES);
        }
        jdbc.sql("""
                        INSERT INTO pr_diffs(pr_id, head_sha, diff, source, byte_size, fetched_at)
                        VALUES (:p, :s, :d, :src, :n, NOW())
                        ON CONFLICT (pr_id, head_sha) DO UPDATE SET diff = EXCLUDED.diff, source = EXCLUDED.source,
                            byte_size = EXCLUDED.byte_size, fetched_at = NOW()""")
                .param("p", prId).param("s", headSha == null ? "" : headSha).param("d", stored)
                .param("src", src).param("n", Math.min(bytes.length, MAX_BYTES))
                .update();
    }
}
