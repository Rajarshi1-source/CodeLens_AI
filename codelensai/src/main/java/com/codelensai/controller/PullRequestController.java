package com.codelensai.controller;

import com.codelensai.model.dto.PrDiffResponse;
import com.codelensai.model.dto.PullRequestDto;
import com.codelensai.model.dto.ReviewCommentDto;
import com.codelensai.model.dto.ReviewSummaryResponse;
import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.User;
import com.codelensai.security.AccessGuard;
import com.codelensai.security.CurrentUser;
import com.codelensai.service.DiffSnapshotService;
import com.codelensai.service.PullRequestService;
import com.codelensai.service.ReviewQueryService;
import com.codelensai.service.ReviewQueueService;
import com.codelensai.websocket.ReviewReplayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pull-request read/act surface. Every endpoint is scoped by repository membership via {@link AccessGuard}:
 * a PR in a repository the caller is not a member of is a 404.
 */
@RestController
@RequestMapping("/api/prs")
public class PullRequestController {

    private final PullRequestService pullRequestService;
    private final ReviewQueryService reviewQueries;
    private final ReviewQueueService reviewQueueService;
    private final DiffSnapshotService diffSnapshots;
    private final ReviewReplayService replay;
    private final AccessGuard guard;

    public PullRequestController(PullRequestService pullRequestService, ReviewQueryService reviewQueries,
                                 ReviewQueueService reviewQueueService, DiffSnapshotService diffSnapshots,
                                 ReviewReplayService replay, AccessGuard guard) {
        this.pullRequestService = pullRequestService;
        this.reviewQueries = reviewQueries;
        this.reviewQueueService = reviewQueueService;
        this.diffSnapshots = diffSnapshots;
        this.replay = replay;
        this.guard = guard;
    }

    /** PRs in the caller's repositories, newest first; optional {@code ?risk=HIGH|MEDIUM|LOW}. */
    @GetMapping
    public List<PullRequestDto> list(@CurrentUser User user, @RequestParam(required = false) String risk) {
        return pullRequestService.listAccessible(user.getId()).stream()
                .filter(pr -> risk == null || risk.isBlank() || risk.equalsIgnoreCase(pr.getRiskLevel()))
                .map(PullRequestDto::from)
                .toList();
    }

    @GetMapping("/{id}")
    public PullRequestDto get(@CurrentUser User user, @PathVariable long id) {
        return PullRequestDto.from(guard.requirePr(id, user.getId()));
    }

    /** Comments of the latest review only, with feedback tallies and apply state. */
    @GetMapping("/{id}/comments")
    public List<ReviewCommentDto> comments(@CurrentUser User user, @PathVariable long id) {
        PullRequest pr = guard.requirePr(id, user.getId());
        return reviewQueries.latestComments(pr, user.getId());
    }

    /** Latest review summary: status, prose, severity counts, risk, tokens and cost. */
    @GetMapping("/{id}/summary")
    public ResponseEntity<ReviewSummaryResponse> summary(@CurrentUser User user, @PathVariable long id) {
        PullRequest pr = guard.requirePr(id, user.getId());
        return reviewQueries.latestSummary(pr).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Unified diff for the PR's head: the persisted snapshot the review ran against, else a GitHub fetch.
     * {@code diff} is "" when unavailable, and the client falls back to its findings list.
     */
    @GetMapping("/{id}/diff")
    public PrDiffResponse diff(@CurrentUser User user, @PathVariable long id) {
        PullRequest pr = guard.requirePr(id, user.getId());
        return new PrDiffResponse(id, pr.getHeadSha(), diffSnapshots.getOrFetch(pr));
    }

    /** REST mirror of the WebSocket replay: stream items after {@code afterSeq} for the current session. */
    @GetMapping("/{id}/stream")
    public ReviewReplayService.ReplayBatch stream(@CurrentUser User user, @PathVariable long id,
                                                  @RequestParam(defaultValue = "0") long afterSeq,
                                                  @RequestParam(defaultValue = "0") long sessionId) {
        guard.requirePr(id, user.getId());
        return replay.replay(id, afterSeq, sessionId);
    }

    @PostMapping("/{id}/re-review")
    public ResponseEntity<String> reReview(@CurrentUser User user, @PathVariable long id) {
        PullRequest pr = guard.requirePr(id, user.getId());
        reviewQueueService.enqueue(pr.getId(), pr.getHeadSha(), true);
        return ResponseEntity.accepted().body("re-review queued");
    }
}
