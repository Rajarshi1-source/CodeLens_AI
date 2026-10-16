package com.codelensai.service.suggestion;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.entity.User;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import com.codelensai.service.GitHubService;
import com.codelensai.service.github.GitHubApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * G6 "Apply suggestion": turns an AI comment's {@code code_suggestion} into a GitHub <i>suggested change</i>
 * — an inline PR review comment with a {@code ```suggestion} block on the exact line of the reviewed
 * commit. The author then commits it with one click in GitHub; CodeLens never needs write access to code.
 *
 * <p>Idempotent: an atomic {@code jsonb_set} claim on the comment's metadata lets exactly one caller post;
 * repeats return the stored result. A failed attempt is recorded as {@code FAILED} and may be retried.
 */
@Service
public class SuggestionApplyService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionApplyService.class);

    public record ApplyResult(String state, Long githubCommentId, String htmlUrl) {
    }

    private final ReviewCommentRepository comments;
    private final ReviewSessionRepository sessions;
    private final PullRequestRepository pullRequests;
    private final GitHubService gitHubService;
    private final GitHubApiClient api;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public SuggestionApplyService(ReviewCommentRepository comments, ReviewSessionRepository sessions,
                                  PullRequestRepository pullRequests, GitHubService gitHubService, GitHubApiClient api,
                                  JdbcClient jdbc, ObjectMapper objectMapper, MeterRegistry meters) {
        this.comments = comments;
        this.sessions = sessions;
        this.pullRequests = pullRequests;
        this.gitHubService = gitHubService;
        this.api = api;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    public ApplyResult apply(long commentId, User actor) {
        ReviewCommentEntity comment = comments.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (comment.getCodeSuggestion() == null || comment.getCodeSuggestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "this comment has no code suggestion");
        }
        ReviewSession session = sessions.findById(comment.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        PullRequest pr = pullRequests.findById(comment.getPrId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (pr.getHeadSha() == null || !pr.getHeadSha().equals(session.getHeadSha())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the PR has new commits since this review — re-run the review before applying");
        }

        int claimed = jdbc.sql("""
                        UPDATE review_comments
                        SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{apply}', '{"state":"PENDING"}'::jsonb)
                        WHERE id = :id AND (metadata -> 'apply' IS NULL OR metadata -> 'apply' ->> 'state' = 'FAILED')""")
                .param("id", commentId).update();
        if (claimed == 0) {
            return current(commentId);
        }

        Repository repo = gitHubService.repoOf(pr)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String body = comment.getCommentText() + "\n\n```suggestion\n" + comment.getCodeSuggestion().stripTrailing() + "\n```";
        try {
            GitHubApiClient.CreatedReviewComment created = api.createReviewComment(repo.getFullName(), pr.getPrNumber(),
                    gitHubService.tokenFor(repo),
                    new GitHubApiClient.NewReviewComment(body, session.getHeadSha(), comment.getFilePath(),
                            comment.getLineNumber(), "RIGHT"));
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("state", "APPLIED");
            state.put("githubCommentId", created.id());
            state.put("htmlUrl", created.htmlUrl());
            state.put("appliedBy", actor.getUsername());
            state.put("appliedAt", Instant.now().toString());
            store(commentId, state);
            meters.counter("suggestion.apply", "result", "applied").increment();
            return new ApplyResult("APPLIED", created.id(), created.htmlUrl());
        } catch (RuntimeException e) {
            log.warn("Apply suggestion failed for comment {}: {}", commentId, e.getMessage());
            store(commentId, Map.of("state", "FAILED", "error", String.valueOf(e.getMessage())));
            meters.counter("suggestion.apply", "result", "failed").increment();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub rejected the suggestion: " + e.getMessage());
        }
    }

    private void store(long commentId, Map<String, Object> state) {
        jdbc.sql("UPDATE review_comments SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{apply}', CAST(:s AS jsonb)) WHERE id = :id")
                .param("s", objectMapper.writeValueAsString(state)).param("id", commentId).update();
    }

    private ApplyResult current(long commentId) {
        String json = jdbc.sql("SELECT metadata -> 'apply' FROM review_comments WHERE id = :id")
                .param("id", commentId).query(String.class).optional().orElse(null);
        if (json == null) {
            return new ApplyResult("PENDING", null, null);
        }
        var node = objectMapper.readTree(json);
        return new ApplyResult(node.path("state").asString("PENDING"),
                node.path("githubCommentId").isNumber() ? node.path("githubCommentId").asLong() : null,
                node.path("htmlUrl").asString(null));
    }
}
