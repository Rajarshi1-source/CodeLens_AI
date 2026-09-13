package com.codelensai;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.repository.ReviewSessionRepository;
import com.codelensai.service.ReviewQueueService;
import com.codelensai.util.StreamKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end exercise of the Redis Streams queue across the Spring Data Redis 4 / Lettuce 7 upgrade:
 * {@link ReviewQueueService#enqueue} produces, the running {@code ReviewJobConsumer} reads through
 * the consumer group, and the entry is acknowledged.
 *
 * <p>{@code ReviewJobConsumer} touches a wide slice of the Streams API — {@code createGroup}, {@code read},
 * {@code pending} (with {@code Range.unbounded()}), {@code claim}, {@code add} and {@code acknowledge} —
 * which made it the most API-exposed file in the Boot 4 migration and the one most likely to break on
 * a Spring Data major. Nothing covered it before.
 *
 * <p>The job is seeded as an <em>already-reviewed</em> {@code (prId, headSha)} pair so the consumer's
 * idempotency short-circuit acknowledges it without calling GitHub or an LLM. Delivery is asserted via
 * the consumer group's {@code last-delivered-id} advancing (pending count alone would also read zero
 * <em>before</em> delivery, so it cannot distinguish "acked" from "never read").
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReviewQueueRoundTripTest {

    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ReviewQueueService reviewQueueService;
    @Autowired
    RepositoryRepository repositoryRepository;
    @Autowired
    PullRequestRepository pullRequestRepository;
    @Autowired
    ReviewSessionRepository reviewSessionRepository;

    @Test
    void enqueuedJobIsDeliveredThroughTheGroupAndAcknowledged() {
        String headSha = "sha" + System.nanoTime();
        long prId = seedAlreadyReviewedPr(headSha);

        String deliveredBefore = lastDeliveredId();

        reviewQueueService.enqueue(prId, headSha);

        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    StreamInfo.XInfoGroup group = group();
                    assertNotNull(group, "consumer group '" + StreamKeys.REVIEW_GROUP + "' is missing");
                    assertNotEquals(deliveredBefore, group.lastDeliveredId(),
                            "consumer never read the enqueued job from the stream");
                    assertEquals(0L, group.pendingCount(),
                            "job was delivered but never acknowledged (still in the PEL)");
                });
    }

    /**
     * A row in {@code review_sessions} for this {@code (prId, headSha)} makes
     * {@code ReviewSessionRepository.existsByPrIdAndHeadSha} true, so the consumer acks and returns
     * instead of attempting a real review.
     */
    private long seedAlreadyReviewedPr(String headSha) {
        Repository repo = new Repository();
        repo.setFullName("acme/queue-roundtrip-" + System.nanoTime());
        repo.setGithubRepoId(System.nanoTime());
        repo = repositoryRepository.saveAndFlush(repo);

        PullRequest pr = new PullRequest();
        pr.setRepoId(repo.getId());
        pr.setGithubPrId(System.nanoTime());
        pr.setPrNumber(1);
        pr.setTitle("chore: round-trip fixture");
        pr.setAuthor("octocat");
        pr.setHeadSha(headSha);
        pr = pullRequestRepository.saveAndFlush(pr);

        ReviewSession session = new ReviewSession();
        session.setPrId(pr.getId());
        session.setHeadSha(headSha);
        session.setStatus(ReviewStatus.COMPLETED);
        session.setModelUsed("local-mock");
        reviewSessionRepository.saveAndFlush(session);

        return pr.getId();
    }

    private String lastDeliveredId() {
        StreamInfo.XInfoGroup group = group();
        return group == null ? "0-0" : group.lastDeliveredId();
    }

    private StreamInfo.XInfoGroup group() {
        try {
            for (StreamInfo.XInfoGroup g : redis.opsForStream().groups(StreamKeys.REVIEW_JOBS)) {
                if (StreamKeys.REVIEW_GROUP.equals(g.groupName())) {
                    return g;
                }
            }
        } catch (Exception streamNotCreatedYet) {
            return null;
        }
        return null;
    }
}
