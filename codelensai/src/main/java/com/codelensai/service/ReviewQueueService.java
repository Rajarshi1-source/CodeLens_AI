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

    public void enqueue(long prId, String headSha) {
        Map<String, String> job = Map.of(
                "prId", String.valueOf(prId),
                "headSha", headSha == null ? "" : headSha);
        redis.opsForStream().add(StreamRecords.mapBacked(job).withStreamKey(StreamKeys.REVIEW_JOBS));
    }
}
