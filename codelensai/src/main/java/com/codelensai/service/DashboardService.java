package com.codelensai.service;

import com.codelensai.model.dto.DashboardStats;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final PullRequestRepository pullRequestRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final ReviewSessionRepository reviewSessionRepository;

    public DashboardService(PullRequestRepository pullRequestRepository,
                            ReviewCommentRepository reviewCommentRepository,
                            ReviewSessionRepository reviewSessionRepository) {
        this.pullRequestRepository = pullRequestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewSessionRepository = reviewSessionRepository;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats() {
        long total = pullRequestRepository.count();
        long reviewed = pullRequestRepository.countByStatus(ReviewStatus.COMPLETED);
        long pending = pullRequestRepository.countByStatus(ReviewStatus.PENDING)
                + pullRequestRepository.countByStatus(ReviewStatus.IN_PROGRESS);

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        for (Object[] row : reviewCommentRepository.countBySeverity()) {
            severityCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        Double avg = reviewSessionRepository.averageCompletedReviewTimeMs();
        return new DashboardStats(total, reviewed, pending, severityCounts, avg);
    }

    @Transactional(readOnly = true)
    public List<Object[]> rawSeverityCounts() {
        return reviewCommentRepository.countBySeverity();
    }
}
