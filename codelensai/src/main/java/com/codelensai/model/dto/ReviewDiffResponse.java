package com.codelensai.model.dto;

import java.util.List;

/** Response for {@code POST /api/internal/review-diff} — the shape the Python eval harness consumes. */
public record ReviewDiffResponse(List<Comment> comments) {

    public record Comment(String file, int line, String severity, String comment, double confidence) {
    }
}
