package com.codelensai.service;

import com.codelensai.model.dto.DashboardStats;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.model.enums.RiskLevel;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import com.codelensai.service.cost.TokenBudgetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard numbers, scoped to the caller's repositories, plus today's LLM spend and budget. */
@Service
public class DashboardService {

    private final PullRequestRepository pullRequestRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final ReviewSessionRepository reviewSessionRepository;
    private final TokenBudgetService budget;

    public DashboardService(PullRequestRepository pullRequestRepository,
                            ReviewCommentRepository reviewCommentRepository,
                            ReviewSessionRepository reviewSessionRepository,
                            TokenBudgetService budget) {
        this.pullRequestRepository = pullRequestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewSessionRepository = reviewSessionRepository;
        this.budget = budget;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats(long userId) {
        long total = pullRequestRepository.countAccessible(userId);
        long reviewed = pullRequestRepository.countAccessibleByStatus(userId, List.of(ReviewStatus.COMPLETED));
        long pending = pullRequestRepository.countAccessibleByStatus(userId,
                List.of(ReviewStatus.PENDING, ReviewStatus.IN_PROGRESS));

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        for (Object[] row : reviewCommentRepository.countBySeverityForUser(userId)) {
            severityCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> risk = new LinkedHashMap<>();
        for (RiskLevel level : RiskLevel.values()) {
            risk.put(level.name(), 0L);
        }
        for (Object[] row : pullRequestRepository.riskDistribution(userId)) {
            risk.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal costToday = nz(reviewSessionRepository.costSinceForUser(userId, startOfDay));
        BigDecimal cost30d = nz(reviewSessionRepository.costSinceForUser(userId, Instant.now().minus(Duration.ofDays(30))));
        Long tokensToday = reviewSessionRepository.tokensSinceForUser(userId, startOfDay);
        TokenBudgetService.Snapshot b = budget.snapshot();
        return new DashboardStats(total, reviewed, pending, severityCounts,
                reviewSessionRepository.averageCompletedReviewTimeMsForUser(userId), risk, costToday, cost30d,
                tokensToday == null ? 0 : tokensToday,
                new DashboardStats.Budget(b.dailyLimit(), b.usedToday(), b.remaining()));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
