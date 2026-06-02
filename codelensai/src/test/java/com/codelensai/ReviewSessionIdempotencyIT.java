package com.codelensai;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.repository.ReviewSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The effectively-once backstop: {@code UNIQUE(pr_id, head_sha)} on {@code review_sessions} rejects a
 * duplicate review for the same commit even if two workers race. The second insert must throw.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReviewSessionIdempotencyIT {

    @Autowired
    RepositoryRepository repositoryRepository;
    @Autowired
    PullRequestRepository pullRequestRepository;
    @Autowired
    ReviewSessionRepository reviewSessionRepository;

    @Test
    void duplicatePrIdHeadShaIsRejected() {
        Repository repo = new Repository();
        repo.setFullName("acme/widgets-" + System.nanoTime());
        repo.setGithubRepoId(System.nanoTime());
        repo = repositoryRepository.saveAndFlush(repo);

        PullRequest pr = new PullRequest();
        pr.setRepoId(repo.getId());
        pr.setGithubPrId(System.nanoTime());
        pr.setPrNumber(1);
        pr.setTitle("feat: thing");
        pr.setAuthor("octocat");
        pr.setHeadSha("deadbeef");
        pr = pullRequestRepository.saveAndFlush(pr);

        String sha = "abc1234";
        reviewSessionRepository.saveAndFlush(session(pr.getId(), sha));

        ReviewSession duplicate = session(pr.getId(), sha);
        assertThrows(DataIntegrityViolationException.class,
                () -> reviewSessionRepository.saveAndFlush(duplicate));
    }

    private ReviewSession session(long prId, String headSha) {
        ReviewSession s = new ReviewSession();
        s.setPrId(prId);
        s.setHeadSha(headSha);
        s.setStatus(ReviewStatus.IN_PROGRESS);
        s.setModelUsed("local-mock");
        return s;
    }
}
