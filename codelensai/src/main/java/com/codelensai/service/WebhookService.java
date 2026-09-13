package com.codelensai.service;

import com.codelensai.model.enums.PrAction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Parses a verified GitHub {@code pull_request} payload and enqueues a review job on the
 * {@code review-jobs} Redis Stream. {@code headSha} rides along so the EFFECT downstream is
 * idempotent on {@code (pr_id, head_sha)}.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final ObjectMapper objectMapper;
    private final PullRequestService pullRequestService;
    private final ReviewQueueService reviewQueueService;

    public WebhookService(ObjectMapper objectMapper,
                          PullRequestService pullRequestService,
                          ReviewQueueService reviewQueueService) {
        this.objectMapper = objectMapper;
        this.pullRequestService = pullRequestService;
        this.reviewQueueService = reviewQueueService;
    }

    public void parseAndEnqueue(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            PrAction action = PrAction.from(root.path("action").asString());
            if (!action.triggersReview()) {
                log.debug("Ignoring pull_request action={}", root.path("action").asString());
                return;
            }

            JsonNode pr = root.path("pull_request");
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
                    pr.path("html_url").asString(null));

            long prId = pullRequestService.upsertPullRequest(upsert);
            String headSha = upsert.headSha();

            reviewQueueService.enqueue(prId, headSha);

            log.info("Enqueued review job prId={} headSha={}", prId, shortSha(headSha));
        } catch (Exception e) {
            log.error("Failed to parse/enqueue webhook", e);
            throw new IllegalStateException("webhook processing failed", e);
        }
    }

    private static String shortSha(String sha) {
        return (sha == null || sha.length() < 7) ? String.valueOf(sha) : sha.substring(0, 7);
    }
}
