package com.codelensai.service;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PullRequestService {

    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;

    public PullRequestService(RepositoryRepository repositoryRepository,
                              PullRequestRepository pullRequestRepository) {
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
    }

    /** Snapshot of the PR fields the webhook carries. */
    public record PullRequestUpsert(
            String repoFullName,
            long githubPrId,
            int prNumber,
            String title,
            String author,
            String branchFrom,
            String branchTo,
            String headSha,
            String htmlUrl) {
    }

    /**
     * Create-or-update the repository + pull request. Returns our internal PR id.
     * Resets status to PENDING so a fresh review is expected for the new head SHA.
     */
    @Transactional
    public long upsertPullRequest(PullRequestUpsert in) {
        Repository repo = repositoryRepository.findByFullName(in.repoFullName())
                .orElseGet(() -> {
                    Repository r = new Repository();
                    r.setFullName(in.repoFullName());
                    r.setGithubRepoId(0L); // populated when the repo is connected via OAuth
                    r.setActive(Boolean.TRUE);
                    return repositoryRepository.save(r);
                });

        PullRequest pr = pullRequestRepository
                .findByRepoIdAndGithubPrId(repo.getId(), in.githubPrId())
                .orElseGet(PullRequest::new);

        pr.setRepoId(repo.getId());
        pr.setGithubPrId(in.githubPrId());
        pr.setPrNumber(in.prNumber());
        pr.setTitle(in.title() == null || in.title().isBlank() ? "(untitled)" : in.title());
        pr.setAuthor(in.author() == null || in.author().isBlank() ? "unknown" : in.author());
        pr.setBranchFrom(in.branchFrom());
        pr.setBranchTo(in.branchTo());
        pr.setHeadSha(in.headSha());
        pr.setHtmlUrl(in.htmlUrl());
        pr.setStatus(ReviewStatus.PENDING);

        return pullRequestRepository.save(pr).getId();
    }

    @Transactional(readOnly = true)
    public List<PullRequest> listRecent() {
        return pullRequestRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<PullRequest> find(long id) {
        return pullRequestRepository.findById(id);
    }

    @Transactional
    public void updateStatus(long prId, ReviewStatus status) {
        pullRequestRepository.findById(prId).ifPresent(pr -> {
            pr.setStatus(status);
            pullRequestRepository.save(pr);
        });
    }
}
