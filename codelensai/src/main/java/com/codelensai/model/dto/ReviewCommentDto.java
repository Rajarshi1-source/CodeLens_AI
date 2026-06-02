package com.codelensai.model.dto;

import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.enums.Severity;

public record ReviewCommentDto(
        long id,
        String filePath,
        int lineNumber,
        Severity severity,
        String commentText,
        String codeSuggestion,
        Double confidence
) {
    public static ReviewCommentDto from(ReviewCommentEntity e) {
        return new ReviewCommentDto(
                e.getId(),
                e.getFilePath(),
                e.getLineNumber() == null ? 0 : e.getLineNumber(),
                e.getSeverity(),
                e.getCommentText(),
                e.getCodeSuggestion(),
                e.getConfidence() == null ? null : e.getConfidence().doubleValue());
    }
}
