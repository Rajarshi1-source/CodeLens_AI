package com.codelensai.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Delivery-level dedup for GitHub webhooks. GitHub delivers at-least-once, so the same
 * {@code X-GitHub-Delivery} UUID can arrive 2-3 times. {@code SET key value NX EX} is atomic:
 * only the first caller for a delivery id wins.
 */
@Service
public class IdempotencyService {

    private static final Duration WEBHOOK_TTL = Duration.ofHours(24);
    private static final String WEBHOOK_KEY_PREFIX = "webhook:seen:";

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** True only for the FIRST delivery with this id (atomic SET NX EX); false for re-deliveries. */
    public boolean isFirstDelivery(String deliveryId) {
        Boolean wasSet = redis.opsForValue()
                .setIfAbsent(WEBHOOK_KEY_PREFIX + deliveryId, "1", WEBHOOK_TTL);
        return Boolean.TRUE.equals(wasSet);
    }
}
