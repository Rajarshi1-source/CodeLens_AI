package com.codelensai.controller;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.User;
import com.codelensai.security.AccessGuard;
import com.codelensai.security.CurrentUser;
import com.codelensai.service.feedback.FeedbackService;
import com.codelensai.service.feedback.SuppressionService;
import com.codelensai.service.suggestion.SuggestionApplyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-comment actions: feedback votes (G7 flywheel) and "apply suggestion" (G6). Membership-checked. */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final AccessGuard guard;
    private final FeedbackService feedback;
    private final SuppressionService suppression;
    private final SuggestionApplyService suggestions;

    public CommentController(AccessGuard guard, FeedbackService feedback, SuppressionService suppression,
                             SuggestionApplyService suggestions) {
        this.guard = guard;
        this.feedback = feedback;
        this.suppression = suppression;
        this.suggestions = suggestions;
    }

    public record FeedbackRequest(@NotNull Integer vote) {
    }

    @PutMapping("/{id}/feedback")
    public FeedbackService.Summary vote(@CurrentUser User user, @PathVariable long id,
                                        @Valid @RequestBody FeedbackRequest body) {
        ReviewCommentEntity comment = guard.requireComment(id, user.getId());
        PullRequest pr = guard.requirePr(comment.getPrId(), user.getId());
        FeedbackService.Summary summary = feedback.vote(comment, pr.getRepoId(), user.getId(), body.vote());
        suppression.invalidate(pr.getRepoId());
        return summary;
    }

    @DeleteMapping("/{id}/feedback")
    public FeedbackService.Summary clear(@CurrentUser User user, @PathVariable long id) {
        ReviewCommentEntity comment = guard.requireComment(id, user.getId());
        return feedback.clear(comment.getId(), user.getId());
    }

    @PostMapping("/{id}/apply")
    public ResponseEntity<SuggestionApplyService.ApplyResult> apply(@CurrentUser User user, @PathVariable long id) {
        guard.requireComment(id, user.getId());
        SuggestionApplyService.ApplyResult result = suggestions.apply(id, user);
        return "PENDING".equals(result.state())
                ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
    }
}
