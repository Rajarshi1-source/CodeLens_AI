package com.codelensai.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed sliding-window rate limiter plus a daily token-budget guard. Bounds outbound LLM/GitHub
 * calls ACROSS pods (complements the in-pod virtual-thread semaphore). Cost runaway protection: a huge
 * repo can't drain the whole token budget.
 */
@Service
public class RateLimiterService {

    private final StringRedisTemplate redis;

    public RateLimiterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Sliding-window allowance using a Redis sorted set: evict entries older than the window, count
     * what remains, and admit the call if under {@code limit}. Each call adds a unique member scored
     * by the current timestamp.
     */
    public boolean allow(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();
        var zset = redis.opsForZSet();
        zset.removeRangeByScore(key, 0, windowStart);
        Long count = zset.zCard(key);
        if (count != null && count >= limit) {
            return false;
        }
        zset.add(key, now + ":" + UUID.randomUUID(), now);
        redis.expire(key, window.toMillis() + 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    /** Track tokens consumed today against a budget. Returns true while under budget. */
    public boolean consumeDailyTokens(long tokens, long dailyBudget) {
        String key = "rate:llm:tokens:" + java.time.LocalDate.now();
        Long total = redis.opsForValue().increment(key, tokens);
        if (total != null && total.equals(tokens)) {
            redis.expire(key, Duration.ofDays(2));
        }
        return total == null || total <= dailyBudget;
    }
}
