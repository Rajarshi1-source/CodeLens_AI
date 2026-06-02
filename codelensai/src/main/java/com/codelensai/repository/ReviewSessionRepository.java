package com.codelensai.repository;

import com.codelensai.model.entity.ReviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {
    boolean existsByPrIdAndHeadSha(Long prId, String headSha);

    Optional<ReviewSession> findByPrIdAndHeadSha(Long prId, String headSha);

    @Query("select avg(s.reviewTimeMs) from ReviewSession s where s.status = com.codelensai.model.enums.ReviewStatus.COMPLETED")
    Double averageCompletedReviewTimeMs();
}
