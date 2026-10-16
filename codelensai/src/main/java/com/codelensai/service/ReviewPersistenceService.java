package com.codelensai.service;

import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

/**
 * Transactional boundary for review state. Completion writes PR status + session + comments + risk in ONE
 * transaction (the CP plane — no ghost states). Lives in its own bean so Spring's {@code @Transactional}
 * proxy applies when called from {@link ReviewService}.
 *
 * <p>Feedback survives a same-SHA re-review: before the old comments are deleted their votes are detached
 * ({@code comment_id = NULL}), and after the new comments are written the votes are re-linked to the
 * comment with the same fingerprint.
 */
@Service
public class ReviewPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ReviewPersistenceService.class);

    private final ReviewSessionRepository sessionRepo;
    private final ReviewCommentRepository commentRepo;
    private final PullRequestRepository pullRequestRepo;
    private final JdbcClient jdbc;

    public ReviewPersistenceService(ReviewSessionRepository sessionRepo,
                                    ReviewCommentRepository commentRepo,
                                    PullRequestRepository pullRequestRepo,
                                    JdbcClient jdbc) {
        this.sessionRepo = sessionRepo;
        this.commentRepo = commentRepo;
        this.pullRequestRepo = pullRequestRepo;
        this.jdbc = jdbc;
    }

    /**
     * Create-or-replace the review session for {@code (prId, headSha)}. Re-reviewing the same SHA clears the
     * old comments (keeping their feedback) and reuses the slot. The {@code UNIQUE(pr_id, head_sha)}
     * constraint is the final backstop against a two-worker race (the loser throws and bails).
     */
    @Transactional
    public ReviewSession startOrReplaceSession(long prId, String headSha, String model, String promptVersion) {
        sessionRepo.findByPrIdAndHeadSha(prId, headSha).ifPresent(existing -> {
            jdbc.sql("""
                            UPDATE comment_feedback SET comment_id = NULL
                            WHERE comment_id IN (SELECT id FROM review_comments WHERE session_id = :s)""")
                    .param("s", existing.getId()).update();
            commentRepo.deleteBySessionId(existing.getId());
            sessionRepo.delete(existing);
            sessionRepo.flush();
        });
        ReviewSession s = new ReviewSession();
        s.setPrId(prId);
        s.setHeadSha(headSha);
        s.setModelUsed(model);
        s.setPromptVersion(promptVersion);
        s.setStatus(ReviewStatus.IN_PROGRESS);
        ReviewSession saved = sessionRepo.saveAndFlush(s);
        pullRequestRepo.findById(prId).ifPresent(pr -> {
            pr.setStatus(ReviewStatus.IN_PROGRESS);
            pr.setLatestSessionId(saved.getId());
            pullRequestRepo.save(pr);
        });
        return saved;
    }

    @Transactional
    public void completeReview(long sessionId, long prId, ReviewOutcome outcome) {
        List<ReviewCommentEntity> entities = outcome.comments().stream()
                .map(c -> toEntity(sessionId, prId, c))
                .toList();
        commentRepo.saveAll(entities);
        commentRepo.flush();

        PullRequest pr = pullRequestRepo.findById(prId).orElse(null);
        Long repoId = pr == null ? null : pr.getRepoId();
        if (repoId != null) {
            relinkFeedback(sessionId, repoId);
        }

        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setStatus(ReviewStatus.COMPLETED);
            s.setReviewTimeMs(outcome.elapsedMs());
            s.setInputTokens((int) Math.min(Integer.MAX_VALUE, outcome.inputTokens()));
            s.setOutputTokens((int) Math.min(Integer.MAX_VALUE, outcome.outputTokens()));
            s.setTotalTokens((int) Math.min(Integer.MAX_VALUE, outcome.totalTokens()));
            s.setCostUsd(outcome.costUsd());
            s.setSummary(outcome.summary());
            s.setMetadata(new HashMap<>(outcome.metadata()));
            if (outcome.risk() != null) {
                s.setRiskScore(outcome.risk().score());
                s.setRiskLevel(outcome.risk().level().name());
                s.setRiskReasons(outcome.risk().reasonMaps());
                s.setRecommendedAction(outcome.risk().recommendedAction());
            }
            s.setFailureReason(null);
            s.setCompletedAt(Instant.now());
            sessionRepo.save(s);
        });
        if (pr != null) {
            pr.setStatus(ReviewStatus.COMPLETED);
            pr.setLatestSessionId(sessionId);
            if (outcome.risk() != null) {
                pr.setRiskLevel(outcome.risk().level().name());
                pr.setRiskScore(outcome.risk().score());
            }
            pullRequestRepo.save(pr);
        }
        log.info("Persisted review prId={} comments={} timeMs={} tokens={} cost=${}", prId,
                outcome.comments().size(), outcome.elapsedMs(), outcome.totalTokens(), outcome.costUsd());
    }

    /** Re-attach detached votes to the new comment with the same fingerprint (one per user + comment). */
    private void relinkFeedback(long sessionId, long repoId) {
        jdbc.sql("""
                        UPDATE comment_feedback f SET comment_id = x.cid
                        FROM (SELECT DISTINCT ON (f2.user_id, c.id) f2.id AS fid, c.id AS cid
                              FROM comment_feedback f2
                              JOIN review_comments c ON c.fingerprint = f2.fingerprint
                              WHERE f2.comment_id IS NULL AND f2.repo_id = :repo AND c.session_id = :sid
                              ORDER BY f2.user_id, c.id, f2.updated_at DESC) x
                        WHERE f.id = x.fid""")
                .param("repo", repoId).param("sid", sessionId)
                .update();
    }

    private ReviewCommentEntity toEntity(long sessionId, long prId, ReviewComment c) {
        ReviewCommentEntity e = new ReviewCommentEntity();
        e.setSessionId(sessionId);
        e.setPrId(prId);
        e.setFilePath(c.filePath());
        e.setLineNumber(c.lineNumber());
        e.setSeverity(c.severity());
        e.setCommentText(c.commentText());
        e.setCodeSuggestion(c.codeSuggestion());
        e.setConfidence(BigDecimal.valueOf(c.confidence()));
        e.setRuleKey(c.ruleKey());
        e.setFingerprint(c.fingerprint());
        return e;
    }

    @Transactional
    public void markFailed(long prId, String headSha, String error, ReviewStatus status, String failureReason) {
        sessionRepo.findByPrIdAndHeadSha(prId, headSha).ifPresent(s -> {
            s.setStatus(status);
            s.setSummary("Review failed: " + error);
            s.setFailureReason(failureReason);
            s.setCompletedAt(Instant.now());
            sessionRepo.save(s);
        });
        pullRequestRepo.findById(prId).ifPresent(pr -> {
            pr.setStatus(status);
            pullRequestRepo.save(pr);
        });
    }
}
