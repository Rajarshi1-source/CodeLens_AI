package com.codelensai.controller;

import com.codelensai.model.dto.ReviewDiffRequest;
import com.codelensai.model.dto.ReviewDiffResponse;
import com.codelensai.service.ReviewService;
import com.codelensai.service.feedback.FeedbackExportService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal, eval-only endpoints, guarded by {@code InternalApiGuardFilter} (disabled unless
 * {@code codelens.internal.enabled=true}; token-protected when {@code codelens.internal.token} is set).
 * The Python review-quality harness posts a diff (optionally with repository context files) and reads back
 * the structured comments, token usage and risk; the feedback export turns dismissed comments into
 * negative eval cases.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalReviewController {

    private final ReviewService reviewService;
    private final FeedbackExportService feedbackExport;

    public InternalReviewController(ReviewService reviewService, FeedbackExportService feedbackExport) {
        this.reviewService = reviewService;
        this.feedbackExport = feedbackExport;
    }

    @PostMapping("/review-diff")
    public ReviewDiffResponse reviewDiff(@Valid @RequestBody ReviewDiffRequest request) {
        ReviewService.DiffReview review = reviewService.reviewDiff(request.diff(), request.contextFiles(),
                !Boolean.FALSE.equals(request.retrieval()));
        List<ReviewDiffResponse.Comment> out = review.comments().stream()
                .map(c -> new ReviewDiffResponse.Comment(c.filePath(), c.lineNumber(), c.severity().name(),
                        c.commentText(), c.confidence(), c.ruleKey(), c.codeSuggestion()))
                .toList();
        return new ReviewDiffResponse(out,
                new ReviewDiffResponse.Usage(review.inputTokens(), review.outputTokens(), review.estimated()),
                new ReviewDiffResponse.Risk(review.risk().score(), review.risk().level().name()),
                review.retrievedSnippets());
    }

    /** NDJSON of consistently-dismissed comments as negative eval candidates ({@code must_not_flag}). */
    @GetMapping(value = "/feedback/export", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public String exportNegatives(@RequestParam(required = false) Long repoId,
                                  @RequestParam(defaultValue = "2") int minDismissals) {
        return feedbackExport.exportNdjson(repoId, minDismissals);
    }
}
