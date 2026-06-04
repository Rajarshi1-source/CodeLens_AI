package com.codelensai.controller;

import com.codelensai.model.dto.ReviewSummaryResponse;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.Severity;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read surface for a single review session. */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewSessionRepository reviewSessionRepository;
    private final ReviewCommentRepository reviewCommentRepository;

    public ReviewController(ReviewSessionRepository reviewSessionRepository,
                            ReviewCommentRepository reviewCommentRepository) {
        this.reviewSessionRepository = reviewSessionRepository;
        this.reviewCommentRepository = reviewCommentRepository;
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<ReviewSummaryResponse> summary(@PathVariable long id) {
        ReviewSession session = reviewSessionRepository.findById(id).orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        List<ReviewCommentEntity> comments = reviewCommentRepository.findBySessionId(id);
        Map<String, Long> counts = severityCounts(comments);
        return ResponseEntity.ok(new ReviewSummaryResponse(
                session.getId(),
                session.getPrId(),
                session.getHeadSha(),
                session.getStatus(),
                session.getModelUsed(),
                session.getPromptVersion(),
                session.getTotalTokens(),
                session.getReviewTimeMs(),
                session.getSummary(),
                counts,
                session.getStartedAt(),
                session.getCompletedAt()));
    }

    private Map<String, Long> severityCounts(List<ReviewCommentEntity> comments) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            counts.put(s.name(), 0L);
        }
        for (ReviewCommentEntity c : comments) {
            if (c.getSeverity() != null) {
                counts.merge(c.getSeverity().name(), 1L, Long::sum);
            }
        }
        return counts;
    }
}
