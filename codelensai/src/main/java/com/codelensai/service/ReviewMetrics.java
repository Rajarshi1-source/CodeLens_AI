package com.codelensai.service;

import com.codelensai.util.StreamKeys;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Exposes a {@code review_queue_pending_total} gauge (depth of the {@code review-jobs} stream) at
 * {@code /actuator/prometheus}, alongside the {@code review.duration} timer and {@code llm.tokens.total}
 * counter recorded by {@link ReviewService}. Grafana alerts when queue depth grows.
 */
@Component
public class ReviewMetrics {

    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redis;

    public ReviewMetrics(MeterRegistry meterRegistry, StringRedisTemplate redis) {
        this.meterRegistry = meterRegistry;
        this.redis = redis;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("review_queue_pending_total", this, ReviewMetrics::queueDepth)
                .description("Depth of the review-jobs Redis Stream")
                .register(meterRegistry);
    }

    double queueDepth() {
        try {
            Long size = redis.opsForStream().size(StreamKeys.REVIEW_JOBS);
            return size == null ? 0d : size.doubleValue();
        } catch (Exception e) {
            return 0d;
        }
    }
}
