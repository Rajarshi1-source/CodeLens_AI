package com.codelensai.repository;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {
    Optional<PullRequest> findByRepoIdAndGithubPrId(Long repoId, Long githubPrId);

    List<PullRequest> findAllByOrderByUpdatedAtDesc();

    long countByStatus(ReviewStatus status);
}
