package com.codelensai.model.dto;

import java.util.Map;

public record DashboardStats(
        long totalPullRequests,
        long reviewedPullRequests,
        long pendingPullRequests,
        Map<String, Long> severityCounts,
        Double avgReviewTimeMs
) {
}
