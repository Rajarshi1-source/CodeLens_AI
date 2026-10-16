package com.codelensai.service;

import com.codelensai.util.StreamKeys;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Single place that enqueues review jobs on the Redis Stream (used by the webhook and re-review). */
@Service
public class ReviewQueueService {

    private final StringRedisTemplate redis;

    public ReviewQueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Webhook-triggered review: skipped by the consumer if this {@code (pr, sha)} was already reviewed. */
    public void enqueue(long prId, String headSha) {
        enqueue(prId, headSha, false);
    }

    /**
     * {@code force=true} is the explicit "Re-review" action: the consumer re-runs the review for the same
     * SHA (replacing the session) instead of treating it as a duplicate delivery.
     */
    public void enqueue(long prId, String headSha, boolean force) {
        Map<String, String> job = Map.of(
                "prId", String.valueOf(prId),
                "headSha", headSha == null ? "" : headSha,
                "force", String.valueOf(force));
        redis.opsForStream().add(StreamRecords.mapBacked(job).withStreamKey(StreamKeys.REVIEW_JOBS));
    }
}
