package com.codelensai.model.dto;

import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.enums.Severity;
import com.codelensai.service.feedback.FeedbackService;

import java.util.Map;

/**
 * A persisted AI comment as the SPA sees it, with its feedback tally (and the caller's own vote) and the
 * state of "apply suggestion" ({@code apply} is null until someone applies it).
 */
public record ReviewCommentDto(
        long id,
        String filePath,
        int lineNumber,
        Severity severity,
        String commentText,
        String codeSuggestion,
        Double confidence,
        String ruleKey,
        String fingerprint,
        Feedback feedback,
        Apply apply
) {
    public record Feedback(long up, long down, Integer myVote) {
    }

    public record Apply(String state, String htmlUrl) {
    }

    public static ReviewCommentDto from(ReviewCommentEntity e, FeedbackService.Summary feedback) {
        FeedbackService.Summary f = feedback == null ? FeedbackService.Summary.NONE : feedback;
        return new ReviewCommentDto(
                e.getId(),
                e.getFilePath(),
                e.getLineNumber() == null ? 0 : e.getLineNumber(),
                e.getSeverity(),
                e.getCommentText(),
                e.getCodeSuggestion(),
                e.getConfidence() == null ? null : e.getConfidence().doubleValue(),
                e.getRuleKey(),
                e.getFingerprint(),
                new Feedback(f.up(), f.down(), f.myVote()),
                applyOf(e.getMetadata()));
    }

    @SuppressWarnings("unchecked")
    static Apply applyOf(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get("apply") instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> apply = (Map<String, Object>) raw;
        Object url = apply.get("htmlUrl");
        return new Apply(String.valueOf(apply.get("state")), url == null ? null : String.valueOf(url));
    }
}
