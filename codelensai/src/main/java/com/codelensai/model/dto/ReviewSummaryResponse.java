package com.codelensai.model.dto;

import com.codelensai.model.enums.ReviewStatus;

import java.time.Instant;
import java.util.Map;

/** Session summary for {@code GET /api/reviews/{id}/summary}: status, prose summary, and severity counts. */
public record ReviewSummaryResponse(
        long sessionId,
        long prId,
        String headSha,
        ReviewStatus status,
        String modelUsed,
        String promptVersion,
        Integer totalTokens,
        Integer reviewTimeMs,
        String summary,
        Map<String, Long> severityCounts,
        Instant startedAt,
        Instant completedAt
) {
}
