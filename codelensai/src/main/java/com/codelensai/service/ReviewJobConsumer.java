package com.codelensai.service;

import com.codelensai.repository.ReviewSessionRepository;
import com.codelensai.util.StreamKeys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Consumes review jobs from the {@code review-jobs} Redis Stream with a consumer group.
 *
 * <p>Redis Streams give AT-LEAST-ONCE delivery (a worker can crash after read, before ack).
 * The EFFECT is made idempotent by keying reviews on {@code (pr_id, head_sha)} and acking ONLY
 * after success: at-least-once delivery + idempotent consumer = effectively-once.
 *
 * <p>Each poll cycle first reclaims this consumer's PENDING (delivered-but-unacked) entries and
 * retries them — re-claiming bumps the XPENDING delivery count, so a job that keeps failing is
 * routed to a dead-letter stream after {@code MAX_RETRIES} attempts and the PR is marked failed.
 * Only then does it read new messages.
 */
@Service
public class ReviewJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobConsumer.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration BLOCK = Duration.ofSeconds(5);
    private static final long PENDING_BATCH = 50L;

    private final StringRedisTemplate redis;
    private final ReviewService reviewService;
    private final ReviewSessionRepository sessionRepo;

    private final String consumerName = "worker-" + System.getenv().getOrDefault("HOSTNAME", "1");
    private final ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "review-job-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public ReviewJobConsumer(StringRedisTemplate redis,
                             ReviewService reviewService,
                             ReviewSessionRepository sessionRepo) {
        this.redis = redis;
        this.reviewService = reviewService;
        this.sessionRepo = sessionRepo;
    }

    @PostConstruct
    void start() {
        ensureGroup();
        loop.execute(this::runLoop);
        log.info("ReviewJobConsumer started as {}", consumerName);
    }

    @PreDestroy
    void stop() {
        running = false;
        loop.shutdownNow();
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(StreamKeys.REVIEW_JOBS, ReadOffset.latest(), StreamKeys.REVIEW_GROUP);
            log.info("Created consumer group '{}' on stream '{}'", StreamKeys.REVIEW_GROUP, StreamKeys.REVIEW_JOBS);
        } catch (Exception e) {
            // BUSYGROUP: the group already exists (normal on restart) — safe to ignore.
            log.debug("Consumer group already present (or create skipped): {}", e.getMessage());
        }
    }

    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                poll();
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.warn("Consumer poll error: {}", e.getMessage());
                sleepQuietly();
            }
        }
    }

    /** One read+process cycle. Package-private so tests can drive it deterministically. */
    @SuppressWarnings("unchecked") // single-element StreamOffset varargs to StreamOperations.read
    void poll() {
        reclaimPending();

        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(StreamKeys.REVIEW_GROUP, consumerName),
                StreamReadOptions.empty().count(1).block(BLOCK),
                StreamOffset.create(StreamKeys.REVIEW_JOBS, ReadOffset.lastConsumed()));

        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            processRecord(record);
        }
    }

    /**
     * Retry this consumer's delivered-but-unacked entries. Re-claiming (XCLAIM) increments the
     * delivery count; once it reaches {@code MAX_RETRIES} the job is dead-lettered instead of retried.
     */
    private void reclaimPending() {
        PendingMessages pending = redis.opsForStream().pending(
                StreamKeys.REVIEW_JOBS,
                Consumer.from(StreamKeys.REVIEW_GROUP, consumerName),
                Range.unbounded(), PENDING_BATCH);

        for (PendingMessage pm : pending) {
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    StreamKeys.REVIEW_JOBS, StreamKeys.REVIEW_GROUP, consumerName,
                    Duration.ZERO, pm.getId());
            if (claimed.isEmpty()) {
                continue; // already acked/removed concurrently
            }
            MapRecord<String, Object, Object> record = claimed.get(0);
            if (pm.getTotalDeliveryCount() >= MAX_RETRIES) {
                deadLetter(record, pm.getTotalDeliveryCount());
            } else {
                processRecord(record);
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        long prId;
        String headSha;
        try {
            prId = Long.parseLong(String.valueOf(body.get("prId")));
            headSha = String.valueOf(body.get("headSha"));
        } catch (RuntimeException malformed) {
            log.error("Malformed review job {} -> DLQ: {}", record.getId(), malformed.getMessage());
            redis.opsForStream().add(StreamRecords.mapBacked(toStringMap(body))
                    .withStreamKey(StreamKeys.REVIEW_DLQ));
            ack(record);
            return;
        }

        try {
            if (sessionRepo.existsByPrIdAndHeadSha(prId, headSha)) {
                log.info("Skipping already-reviewed prId={} sha={}", prId, shortSha(headSha));
                ack(record);
                return;
            }
            reviewService.processReview(prId, headSha);
            ack(record);
        } catch (Exception e) {
            // Leave unacked: reclaimPending() will retry it next cycle (and DLQ it after MAX_RETRIES).
            log.warn("Review failed prId={} sha={} (will retry): {}", prId, shortSha(headSha), e.getMessage());
        }
    }

    private void deadLetter(MapRecord<String, Object, Object> record, long attempts) {
        Map<Object, Object> body = record.getValue();
        long prId = parseLongOrDefault(body.get("prId"), -1);
        String headSha = String.valueOf(body.get("headSha"));

        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                        "prId", String.valueOf(prId),
                        "headSha", headSha == null ? "" : headSha,
                        "attempts", String.valueOf(attempts)))
                .withStreamKey(StreamKeys.REVIEW_DLQ));
        ack(record);
        if (prId >= 0) {
            reviewService.markFailed(prId, headSha, "exceeded " + MAX_RETRIES + " review attempts");
        }
        log.error("Moved prId={} to DLQ after {} attempts", prId, attempts);
    }

    private void ack(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(StreamKeys.REVIEW_JOBS, StreamKeys.REVIEW_GROUP, record.getId());
    }

    private static Map<String, String> toStringMap(Map<Object, Object> body) {
        return body.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
    }

    private static long parseLongOrDefault(Object value, long fallback) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shortSha(String sha) {
        return (sha == null || sha.length() < 7) ? String.valueOf(sha) : sha.substring(0, 7);
    }
}
