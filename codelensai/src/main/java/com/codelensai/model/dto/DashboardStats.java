package com.codelensai.model.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Dashboard numbers, scoped to the repositories the caller is a member of. */
public record DashboardStats(
        long totalPullRequests,
        long reviewedPullRequests,
        long pendingPullRequests,
        Map<String, Long> severityCounts,
        Double avgReviewTimeMs,
        Map<String, Long> riskDistribution,
        BigDecimal costTodayUsd,
        BigDecimal cost30dUsd,
        long tokensToday,
        Budget budget
) {
    public record Budget(long dailyLimit, long usedToday, long remaining) {
    }
}
