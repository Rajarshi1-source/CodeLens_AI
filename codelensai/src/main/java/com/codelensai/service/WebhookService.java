package com.codelensai.service;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.enums.PrAction;
import com.codelensai.service.index.IndexQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * Parses a verified GitHub {@code pull_request} payload. Opened/synchronized/reopened PRs enqueue a review
 * job on the {@code review-jobs} Redis Stream ({@code headSha} rides along so the EFFECT downstream is
 * idempotent on {@code (pr_id, head_sha)}). A <b>merged</b> PR enqueues a re-index of its changed paths on
 * the base branch, keeping the G1 code index current without ever indexing unmerged code.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final ObjectMapper objectMapper;
    private final PullRequestService pullRequestService;
    private final ReviewQueueService reviewQueueService;
    private final IndexQueueService indexQueueService;
    private final GitHubService gitHubService;

    public WebhookService(ObjectMapper objectMapper,
                          PullRequestService pullRequestService,
                          ReviewQueueService reviewQueueService,
                          IndexQueueService indexQueueService,
                          GitHubService gitHubService) {
        this.objectMapper = objectMapper;
        this.pullRequestService = pullRequestService;
        this.reviewQueueService = reviewQueueService;
        this.indexQueueService = indexQueueService;
        this.gitHubService = gitHubService;
    }

    /** {@code repository.full_name} from an (as yet unverified) payload — used only to pick the HMAC secret. */
    public Optional<String> repositoryOf(String rawBody) {
        try {
            String name = objectMapper.readTree(rawBody).path("repository").path("full_name").asString("");
            return name.isBlank() ? Optional.empty() : Optional.of(name);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void parseAndEnqueue(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            PrAction action = PrAction.from(root.path("action").asString());
            JsonNode pr = root.path("pull_request");
            if (action == PrAction.CLOSED) {
                onClosed(root, pr);
                return;
            }
            if (!action.triggersReview()) {
                log.debug("Ignoring pull_request action={}", root.path("action").asString());
                return;
            }
            JsonNode head = pr.path("head");
            JsonNode base = pr.path("base");
            var upsert = new PullRequestService.PullRequestUpsert(
                    root.path("repository").path("full_name").asString(),
                    pr.path("id").asLong(),
                    pr.path("number").asInt(),
                    pr.path("title").asString(),
                    pr.path("user").path("login").asString(),
                    head.path("ref").asString(null),
                    base.path("ref").asString(null),
                    head.path("sha").asString(),
                    pr.path("html_url").asString(null),
                    pr.path("changed_files").asInt(0),
                    pr.path("additions").asInt(0),
                    pr.path("deletions").asInt(0));
            long prId = pullRequestService.upsertPullRequest(upsert);
            reviewQueueService.enqueue(prId, upsert.headSha());
            log.info("Enqueued review job prId={} headSha={}", prId, shortSha(upsert.headSha()));
        } catch (Exception e) {
            log.error("Failed to parse/enqueue webhook", e);
            throw new IllegalStateException("webhook processing failed", e);
        }
    }

    private void onClosed(JsonNode root, JsonNode pr) {
        if (!pr.path("merged").asBoolean(false)) {
            return;
        }
        String repoName = root.path("repository").path("full_name").asString("");
        Optional<PullRequest> known = pullRequestService.findByRepoAndGithubPrId(repoName, pr.path("id").asLong());
        if (known.isEmpty() || known.get().getRepoId() == null) {
            return;
        }
        List<String> paths = gitHubService.listPullFiles(known.get()).stream().map(f -> f.filename()).toList();
        String baseRef = pr.path("base").path("ref").asString("");
        indexQueueService.enqueuePaths(known.get().getRepoId(), baseRef, paths);
        log.info("PR {} merged into {}: re-indexing {} changed path(s)", known.get().getId(), baseRef, paths.size());
    }

    private static String shortSha(String sha) {
        return (sha == null || sha.length() < 7) ? String.valueOf(sha) : sha.substring(0, 7);
    }
}
