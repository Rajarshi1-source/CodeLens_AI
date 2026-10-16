package com.codelensai.repository;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    Optional<PullRequest> findByRepoIdAndGithubPrId(Long repoId, Long githubPrId);

    List<PullRequest> findAllByOrderByUpdatedAtDesc();

    long countByStatus(ReviewStatus status);

    /** A PR the user may see (member of its repository), else empty. */
    @Query("""
            select pr from PullRequest pr where pr.id = :id and exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)""")
    Optional<PullRequest> findAccessible(@Param("id") long id, @Param("uid") long uid);

    @Query("""
            select pr from PullRequest pr where exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)
            order by pr.updatedAt desc""")
    List<PullRequest> findAccessible(@Param("uid") long uid);

    @Query("""
            select count(pr) from PullRequest pr where exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)""")
    long countAccessible(@Param("uid") long uid);

    @Query("""
            select count(pr) from PullRequest pr where pr.status in :statuses and exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)""")
    long countAccessibleByStatus(@Param("uid") long uid, @Param("statuses") List<ReviewStatus> statuses);

    @Query("""
            select pr.riskLevel, count(pr) from PullRequest pr where pr.riskLevel is not null and exists (
                select 1 from RepositoryMember m where m.repoId = pr.repoId and m.userId = :uid)
            group by pr.riskLevel""")
    List<Object[]> riskDistribution(@Param("uid") long uid);
}
