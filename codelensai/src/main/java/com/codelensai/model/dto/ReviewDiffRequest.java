package com.codelensai.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request body for the internal eval-only endpoint {@code POST /api/internal/review-diff}.
 * {@code contextFiles} (path → content) stand in for the repository, so cross-file cases can be evaluated
 * with G1 retrieval; {@code retrieval=false} runs the same case without it (ablation).
 */
public record ReviewDiffRequest(
        @NotBlank String diff,
        String language,
        Map<String, String> contextFiles,
        Boolean retrieval
) {
}
