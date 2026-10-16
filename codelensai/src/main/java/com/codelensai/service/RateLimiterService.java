package com.codelensai.service;

import com.codelensai.service.cost.TokenBudgetService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis-backed sliding-window rate limiter. Bounds outbound LLM/GitHub calls ACROSS pods (complements the
 * in-pod bulkhead). The window check and the insert run in one Lua script, so two pods can never both
 * squeeze past the limit between "count" and "add". The daily token budget lives in
 * {@link TokenBudgetService}.
 */
@Service
public class RateLimiterService {

    private static final DefaultRedisScript<Long> SLIDING_WINDOW = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, tonumber(ARGV[1]) - tonumber(ARGV[2]))
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) + 1000)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final TokenBudgetService tokenBudget;

    public RateLimiterService(StringRedisTemplate redis, TokenBudgetService tokenBudget) {
        this.redis = redis;
        this.tokenBudget = tokenBudget;
    }

    /** Admit the call if fewer than {@code limit} calls happened in the trailing {@code window}. */
    public boolean allow(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        Long admitted = redis.execute(SLIDING_WINDOW, List.of(key),
                String.valueOf(now), String.valueOf(window.toMillis()), String.valueOf(limit),
                now + ":" + UUID.randomUUID());
        return admitted != null && admitted == 1L;
    }

    /**
     * Charge {@code tokens} against today's budget ({@code codelens.llm.budget.daily-tokens}). Returns false
     * (and charges nothing) when it would exceed it. The review path reserves up front via
     * {@link TokenBudgetService} instead; this is for callers that account after the fact.
     */
    public boolean consumeDailyTokens(long tokens) {
        try {
            tokenBudget.reserve(tokens, false);
            return true;
        } catch (com.codelensai.service.cost.BudgetExceededException e) {
            return false;
        }
    }
}
