package com.codelensai.service.github;

import com.codelensai.config.IndexingProperties;
import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.service.GitHubService;
import com.codelensai.service.code.FileContentProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A lazy {@link FileContentProvider} over a PR's head commit, so structure-aware chunking (G4) can see the
 * functions around each hunk. One recursive tree call maps paths to blob shas; blobs are fetched only for
 * files the chunker asks about, cached in Redis by {@code (repo, sha, path)} for an hour and capped by
 * size and count so a huge PR cannot fan out into hundreds of API calls.
 */
@Service
public class HeadContentsService {

    static final int MAX_FILES_PER_REVIEW = 50;
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final GitHubApiClient api;
    private final GitHubService gitHubService;
    private final StringRedisTemplate redis;
    private final IndexingProperties indexing;

    public HeadContentsService(GitHubApiClient api, GitHubService gitHubService, StringRedisTemplate redis,
                               IndexingProperties indexing) {
        this.api = api;
        this.gitHubService = gitHubService;
        this.redis = redis;
        this.indexing = indexing;
    }

    public FileContentProvider forPullRequest(PullRequest pr) {
        Optional<Repository> repo = gitHubService.repoOf(pr);
        if (repo.isEmpty() || pr.getHeadSha() == null || pr.getHeadSha().isBlank()) {
            return FileContentProvider.none();
        }
        String fullName = repo.get().getFullName();
        String token = gitHubService.tokenFor(repo.get());
        if (token == null || token.isBlank()) {
            return FileContentProvider.none(); // only fetch with the owner's credentials
        }
        Map<String, GitHubApiClient.RepoTree.Entry> blobs = api.getTree(fullName, pr.getHeadSha(), token)
                .map(t -> t.entries().stream().filter(GitHubApiClient.RepoTree.Entry::isBlob)
                        .collect(Collectors.toMap(GitHubApiClient.RepoTree.Entry::path, Function.identity(), (a, b) -> a)))
                .orElse(Map.of());
        if (blobs.isEmpty()) {
            return FileContentProvider.none();
        }
        AtomicInteger fetched = new AtomicInteger();
        Map<String, Optional<String>> memo = new ConcurrentHashMap<>();
        long repoId = repo.get().getId();
        String headSha = pr.getHeadSha();
        return path -> memo.computeIfAbsent(path, p -> {
            GitHubApiClient.RepoTree.Entry e = blobs.get(p);
            if (e == null || e.size() > indexing.maxFileBytes()) {
                return Optional.empty();
            }
            String key = "gh:file:" + repoId + ":" + headSha + ":" + p;
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Optional.of(cached);
            }
            if (fetched.incrementAndGet() > MAX_FILES_PER_REVIEW) {
                return Optional.empty();
            }
            Optional<String> content = api.getBlob(fullName, e.sha(), token);
            content.ifPresent(c -> redis.opsForValue().set(key, c, CACHE_TTL));
            return content;
        });
    }
}
