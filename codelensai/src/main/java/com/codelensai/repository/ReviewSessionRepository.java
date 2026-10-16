package com.codelensai.repository;

import com.codelensai.model.entity.ReviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {

    boolean existsByPrIdAndHeadSha(Long prId, String headSha);

    Optional<ReviewSession> findByPrIdAndHeadSha(Long prId, String headSha);

    Optional<ReviewSession> findFirstByPrIdOrderByStartedAtDesc(Long prId);

    @Query("select avg(s.reviewTimeMs) from ReviewSession s where s.status = com.codelensai.model.enums.ReviewStatus.COMPLETED")
    Double averageCompletedReviewTimeMs();

    @Query("""
            select avg(s.reviewTimeMs) from ReviewSession s, PullRequest pr
            where s.prId = pr.id and s.status = com.codelensai.model.enums.ReviewStatus.COMPLETED and exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)""")
    Double averageCompletedReviewTimeMsForUser(@Param("uid") long uid);

    @Query("""
            select coalesce(sum(s.costUsd), 0) from ReviewSession s, PullRequest pr
            where s.prId = pr.id and s.startedAt >= :since and exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)""")
    BigDecimal costSinceForUser(@Param("uid") long uid, @Param("since") Instant since);

    @Query("""
            select coalesce(sum(s.totalTokens), 0) from ReviewSession s, PullRequest pr
            where s.prId = pr.id and s.startedAt >= :since and exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)""")
    Long tokensSinceForUser(@Param("uid") long uid, @Param("since") Instant since);
}
