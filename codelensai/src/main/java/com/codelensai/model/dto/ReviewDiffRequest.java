package com.codelensai.model.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for the internal eval-only endpoint {@code POST /api/internal/review-diff}. */
public record ReviewDiffRequest(
        @NotBlank String diff,
        String language
) {
}
