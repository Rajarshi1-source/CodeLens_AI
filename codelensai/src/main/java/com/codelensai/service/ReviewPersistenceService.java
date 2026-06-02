package com.codelensai.service;

import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Transactional boundary for review state. Completion writes PR status + session + comments in ONE
 * transaction (the CP plane — no ghost states). Lives in its own bean so Spring's {@code @Transactional}
 * proxy applies when called from {@link ReviewService}.
 */
@Service
public class ReviewPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ReviewPersistenceService.class);

    private final ReviewSessionRepository sessionRepo;
    private final ReviewCommentRepository commentRepo;
    private final PullRequestRepository pullRequestRepo;

    public ReviewPersistenceService(ReviewSessionRepository sessionRepo,
                                    ReviewCommentRepository commentRepo,
                                    PullRequestRepository pullRequestRepo) {
        this.sessionRepo = sessionRepo;
        this.commentRepo = commentRepo;
        this.pullRequestRepo = pullRequestRepo;
    }

    /**
     * Create-or-replace the review session for {@code (prId, headSha)}. Re-reviewing the same SHA
     * clears the old comments and reuses the slot. The {@code UNIQUE(pr_id, head_sha)} constraint is
     * the final backstop against a two-worker race (the loser throws and bails).
     */
    @Transactional
    public ReviewSession startOrReplaceSession(long prId, String headSha, String model, String promptVersion) {
        sessionRepo.findByPrIdAndHeadSha(prId, headSha).ifPresent(existing -> {
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
        return sessionRepo.save(s);
    }

    @Transactional
    public void completeReview(long sessionId, long prId, List<ReviewComment> comments,
                              int elapsedMs, int totalTokens, String summary) {
        List<ReviewComment> safeComments = comments == null ? List.of() : comments;
        List<ReviewCommentEntity> entities = safeComments.stream()
                .map(c -> toEntity(sessionId, prId, c))
                .toList();
        commentRepo.saveAll(entities);

        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setStatus(ReviewStatus.COMPLETED);
            s.setReviewTimeMs(elapsedMs);
            s.setTotalTokens(totalTokens);
            s.setSummary(summary);
            s.setCompletedAt(Instant.now());
            sessionRepo.save(s);
        });

        setPrStatus(prId, ReviewStatus.COMPLETED);
        log.info("Persisted review prId={} comments={} timeMs={}", prId, safeComments.size(), elapsedMs);
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
        return e;
    }

    @Transactional
    public void markFailed(long prId, String headSha, String error, ReviewStatus status) {
        sessionRepo.findByPrIdAndHeadSha(prId, headSha).ifPresent(s -> {
            s.setStatus(status);
            s.setSummary("Review failed: " + error);
            s.setCompletedAt(Instant.now());
            sessionRepo.save(s);
        });
        setPrStatus(prId, status);
    }

    private void setPrStatus(long prId, ReviewStatus status) {
        pullRequestRepo.findById(prId).ifPresent(pr -> {
            pr.setStatus(status);
            pullRequestRepo.save(pr);
        });
    }
}
