package com.codelensai.service;

import com.codelensai.model.dto.ReviewCommentDto;
import com.codelensai.model.dto.ReviewSummaryResponse;
import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.Severity;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import com.codelensai.service.feedback.FeedbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read models for the PR detail page: the latest review's comments (with feedback) and its summary. */
@Service
public class ReviewQueryService {

    private final ReviewSessionRepository sessions;
    private final ReviewCommentRepository comments;
    private final FeedbackService feedback;

    public ReviewQueryService(ReviewSessionRepository sessions, ReviewCommentRepository comments,
                              FeedbackService feedback) {
        this.sessions = sessions;
        this.comments = comments;
        this.feedback = feedback;
    }

    /** Comments of the PR's latest review only (older sessions for previous head SHAs are not mixed in). */
    @Transactional(readOnly = true)
    public List<ReviewCommentDto> latestComments(PullRequest pr, long userId) {
        Long sessionId = latestSessionId(pr).orElse(null);
        if (sessionId == null) {
            return List.of();
        }
        List<ReviewCommentEntity> rows = comments.findBySessionIdOrderByFilePathAscLineNumberAsc(sessionId);
        Map<Long, FeedbackService.Summary> votes = feedback.summaries(rows.stream().map(ReviewCommentEntity::getId).toList(), userId);
        return rows.stream().map(c -> ReviewCommentDto.from(c, votes.get(c.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ReviewSummaryResponse> latestSummary(PullRequest pr) {
        return latestSessionId(pr).flatMap(sessions::findById).map(this::summaryOf);
    }

    @Transactional(readOnly = true)
    public ReviewSummaryResponse summaryOf(ReviewSession s) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Severity sev : Severity.values()) {
            counts.put(sev.name(), 0L);
        }
        for (ReviewCommentEntity c : comments.findBySessionId(s.getId())) {
            if (c.getSeverity() != null) {
                counts.merge(c.getSeverity().name(), 1L, Long::sum);
            }
        }
        Map<String, Object> meta = s.getMetadata() == null ? Map.of() : s.getMetadata();
        return new ReviewSummaryResponse(
                s.getId(), s.getPrId(), s.getHeadSha(), s.getStatus(), s.getModelUsed(), s.getPromptVersion(),
                s.getTotalTokens(), s.getInputTokens(), s.getOutputTokens(), s.getCostUsd(), s.getReviewTimeMs(),
                s.getSummary(), counts, s.getRiskScore(), s.getRiskLevel(), s.getRiskReasons(),
                s.getRecommendedAction(), asMap(meta.get("servedBy")), s.getFailureReason(),
                asMap(meta.get("retrieval")), s.getStartedAt(), s.getCompletedAt());
    }

    private Optional<Long> latestSessionId(PullRequest pr) {
        if (pr.getLatestSessionId() != null) {
            return Optional.of(pr.getLatestSessionId());
        }
        return sessions.findFirstByPrIdOrderByStartedAtDesc(pr.getId()).map(ReviewSession::getId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
