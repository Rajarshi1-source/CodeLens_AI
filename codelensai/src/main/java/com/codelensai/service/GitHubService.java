package com.codelensai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Talks to the GitHub REST API (fetch PR diffs, post review summaries).
 *
 * <p>MVP stub: the real WebClient implementation (fetch via {@code diff_url}, ETag/diff caching,
 * post summary, Resilience4j {@code github} circuit breaker/timeout) is a follow-up milestone.
 * Returning an empty diff lets the webhook -> queue -> review pipeline run end-to-end offline;
 * the {@code /api/internal/review-diff} endpoint exercises the full chunk -> provider path with a
 * real diff supplied directly.
 */
@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    public String fetchPrDiff(long prId) {
        log.warn("GitHubService.fetchPrDiff is a stub (prId={}); returning empty diff. "
                + "Wire the real GitHub API client to fetch the unified diff.", prId);
        return "";
    }

    public void postReviewSummary(long prId, String summary) {
        log.debug("GitHubService.postReviewSummary stub (prId={}): {}", prId, summary);
    }
}
