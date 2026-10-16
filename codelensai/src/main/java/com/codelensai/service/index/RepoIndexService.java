package com.codelensai.service.index;

import com.codelensai.config.IndexingProperties;
import com.codelensai.model.entity.Repository;
import com.codelensai.repository.CodeIndexRepository;
import com.codelensai.repository.CodeIndexRepository.IndexChunk;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.service.DiffChunkerService;
import com.codelensai.service.GitHubService;
import com.codelensai.service.embedding.EmbeddingProvider;
import com.codelensai.service.github.GitHubApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Builds and maintains the G1 code index for a repository: fetch files (default branch), cut them into
 * structural chunks, embed them, and upsert chunks + symbols into pgvector.
 *
 * <p>Only the <b>default branch</b> is indexed — never a PR's head — so one PR's unmerged code can never
 * leak into the context retrieved for another PR. After a PR merges, just its changed paths are
 * re-indexed against the base ref.
 */
@Service
public class RepoIndexService {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexService.class);
    private static final int EMBED_MAX_CHARS = 8000;

    private final RepositoryRepository repositories;
    private final CodeIndexRepository index;
    private final CodeChunker chunker;
    private final EmbeddingProvider embedding;
    private final GitHubApiClient api;
    private final GitHubService gitHubService;
    private final DiffChunkerService diffChunker;
    private final IndexingProperties props;
    private final MeterRegistry meters;

    public RepoIndexService(RepositoryRepository repositories, CodeIndexRepository index, CodeChunker chunker,
                            EmbeddingProvider embedding, GitHubApiClient api, GitHubService gitHubService,
                            DiffChunkerService diffChunker, IndexingProperties props, MeterRegistry meters) {
        this.repositories = repositories;
        this.index = index;
        this.chunker = chunker;
        this.embedding = embedding;
        this.api = api;
        this.gitHubService = gitHubService;
        this.diffChunker = diffChunker;
        this.props = props;
        this.meters = meters;
    }

    /** Index the whole default branch. */
    public void indexFull(long repoId) {
        Repository repo = repositories.findById(repoId).orElseThrow();
        String token = gitHubService.tokenFor(repo);
        String branch = api.getRepo(repo.getFullName(), token).map(GitHubApiClient.RepoInfo::defaultBranch)
                .orElse(repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch());
        Optional<GitHubApiClient.RepoTree> tree = api.getTree(repo.getFullName(), branch, token);
        if (tree.isEmpty()) {
            markStatus(repo, "FAILED", null, null);
            log.warn("Index of {} failed: no tree for {}", repo.getFullName(), branch);
            return;
        }
        if (tree.get().truncated()) {
            log.warn("Tree for {}@{} was truncated by GitHub; indexing the entries returned", repo.getFullName(), branch);
        }
        repo.setDefaultBranch(branch);
        Map<String, String> files = fetch(repo.getFullName(), token, selectEntries(tree.get().entries()));
        indexFiles(repo, tree.get().sha(), files, true);
    }

    /** Re-index specific paths at {@code ref} (after a merge). Paths missing from the tree are dropped. */
    public void indexPaths(long repoId, String ref, Collection<String> paths) {
        Repository repo = repositories.findById(repoId).orElseThrow();
        String token = gitHubService.tokenFor(repo);
        Optional<GitHubApiClient.RepoTree> tree = api.getTree(repo.getFullName(), ref, token);
        if (tree.isEmpty()) {
            return;
        }
        Map<String, GitHubApiClient.RepoTree.Entry> byPath = new LinkedHashMap<>();
        tree.get().entries().forEach(e -> byPath.put(e.path(), e));
        List<GitHubApiClient.RepoTree.Entry> wanted = new ArrayList<>();
        for (String p : paths) {
            GitHubApiClient.RepoTree.Entry e = byPath.get(p);
            if (e == null) {
                index.deleteStale(repoId, p, List.of());
            } else {
                wanted.add(e);
            }
        }
        Map<String, String> files = fetch(repo.getFullName(), token, selectEntries(wanted));
        indexFiles(repo, tree.get().sha(), files, false);
    }

    /** Index supplied file contents (demo seeding, fixtures). Replaces the whole index. */
    public void indexFromContents(long repoId, String ref, Map<String, String> files) {
        Repository repo = repositories.findById(repoId).orElseThrow();
        indexFiles(repo, ref, files, true);
    }

    private void indexFiles(Repository repo, String ref, Map<String, String> files, boolean fullReplace) {
        long t0 = System.nanoTime();
        markStatus(repo, "INDEXING", null, null);
        try {
            for (Map.Entry<String, String> f : files.entrySet()) {
                List<IndexChunk> chunks = chunker.chunk(f.getKey(), f.getValue());
                if (chunks.isEmpty()) {
                    index.deleteStale(repo.getId(), f.getKey(), List.of());
                    continue;
                }
                List<float[]> vectors = embedding.embedBatch(chunks.stream()
                        .map(c -> embedText(c)).toList());
                List<String> keep = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    index.upsertChunk(repo.getId(), chunks.get(i), ref, vectors.get(i), embedding.providerId());
                    keep.add(chunks.get(i).contentHash());
                }
                index.deleteStale(repo.getId(), f.getKey(), keep);
            }
            if (fullReplace) {
                index.deletePathsNotIn(repo.getId(), files.keySet());
            }
            int total = index.countChunks(repo.getId());
            markStatus(repo, "READY", ref, total);
            meters.timer("index.duration").record(Duration.ofNanos(System.nanoTime() - t0));
            meters.counter("index.chunks").increment(total);
            log.info("Indexed {} ({} file(s), {} chunk(s)) at {}", repo.getFullName(), files.size(), total, ref);
        } catch (RuntimeException e) {
            markStatus(repo, "FAILED", null, null);
            throw e;
        }
    }

    private static String embedText(IndexChunk c) {
        String text = c.path() + "\n" + (c.symbol() == null ? "" : c.symbol() + "\n") + c.content();
        return text.length() > EMBED_MAX_CHARS ? text.substring(0, EMBED_MAX_CHARS) : text;
    }

    private List<GitHubApiClient.RepoTree.Entry> selectEntries(List<GitHubApiClient.RepoTree.Entry> entries) {
        return entries.stream()
                .filter(GitHubApiClient.RepoTree.Entry::isBlob)
                .filter(e -> e.size() <= props.maxFileBytes())
                .filter(e -> !diffChunker.shouldSkip(e.path()))
                .filter(e -> props.extensions().contains(extension(e.path())))
                .limit(props.maxFiles())
                .toList();
    }

    private Map<String, String> fetch(String fullName, String token, List<GitHubApiClient.RepoTree.Entry> entries) {
        Map<String, String> out = new LinkedHashMap<>();
        Semaphore permits = new Semaphore(props.fetchConcurrency());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Map.Entry<String, Future<Optional<String>>>> futures = new ArrayList<>();
            for (GitHubApiClient.RepoTree.Entry e : entries) {
                futures.add(Map.entry(e.path(), pool.submit(() -> {
                    permits.acquire();
                    try {
                        return api.getBlob(fullName, e.sha(), token);
                    } finally {
                        permits.release();
                    }
                })));
            }
            for (Map.Entry<String, Future<Optional<String>>> f : futures) {
                try {
                    f.getValue().get().ifPresent(content -> out.put(f.getKey(), content));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    log.debug("Skipping {}: {}", f.getKey(), ex.getMessage());
                }
            }
        }
        return out;
    }

    private void markStatus(Repository repo, String status, String ref, Integer chunks) {
        repo.setIndexStatus(status);
        if (ref != null) {
            repo.setIndexedSha(ref.length() > 40 ? ref.substring(0, 40) : ref);
            repo.setIndexedAt(Instant.now());
        }
        if (chunks != null) {
            repo.setIndexedChunks(chunks);
        }
        repositories.save(repo);
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
    }
}
