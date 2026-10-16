package com.codelensai.model.dto;

import com.codelensai.model.enums.ReviewStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A review session as {@code GET /api/reviews/{id}/summary} and {@code GET /api/prs/{id}/summary} return
 * it: status, prose summary, severity counts, the G3 risk assessment, and real token/cost accounting.
 */
public record ReviewSummaryResponse(
        long sessionId,
        long prId,
        String headSha,
        ReviewStatus status,
        String modelUsed,
        String promptVersion,
        Integer totalTokens,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd,
        Integer reviewTimeMs,
        String summary,
        Map<String, Long> severityCounts,
        Integer riskScore,
        String riskLevel,
        List<Map<String, Object>> riskReasons,
        String recommendedAction,
        Map<String, Object> servedBy,
        String failureReason,
        Map<String, Object> retrieval,
        Instant startedAt,
        Instant completedAt
) {
}
