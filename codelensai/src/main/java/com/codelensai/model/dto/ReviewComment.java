package com.codelensai.model.dto;

import com.codelensai.model.enums.Severity;

/** A completed, schema-validated comment produced by the review pipeline. Invariants enforced in the compact constructor. */
public record ReviewComment(
        String filePath,
        int lineNumber,
        Severity severity,
        String commentText,
        String codeSuggestion,
        double confidence
) {
    public ReviewComment {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath required");
        }
        if (lineNumber < 0) {
            throw new IllegalArgumentException("lineNumber must be >= 0");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity required");
        }
        if (commentText == null || commentText.isBlank()) {
            throw new IllegalArgumentException("commentText required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
    }
}
