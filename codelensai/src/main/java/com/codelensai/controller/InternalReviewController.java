package com.codelensai.controller;

import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.dto.ReviewDiffRequest;
import com.codelensai.model.dto.ReviewDiffResponse;
import com.codelensai.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal, eval-only endpoint. The Python review-quality harness posts a diff and reads back the
 * structured comments — this is how "how good are the reviews?" is measured against planted bugs.
 * Not part of the public API; behind {@code /api/internal/**} (permitted for the harness).
 */
@RestController
@RequestMapping("/api/internal")
public class InternalReviewController {

    private final ReviewService reviewService;

    public InternalReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/review-diff")
    public ReviewDiffResponse reviewDiff(@Valid @RequestBody ReviewDiffRequest request) {
        List<ReviewComment> comments = reviewService.reviewDiff(request.diff(), request.language());
        List<ReviewDiffResponse.Comment> out = comments.stream()
                .map(c -> new ReviewDiffResponse.Comment(
                        c.filePath(), c.lineNumber(), c.severity().name(), c.commentText(), c.confidence()))
                .toList();
        return new ReviewDiffResponse(out);
    }
}
