package com.codelensai;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.service.AIReviewService;
import com.codelensai.service.cost.BudgetExceededException;
import com.codelensai.service.cost.TokenBudgetService;
import com.codelensai.support.CodelensIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The primary is Claude pointed at a dead address: retries are exhausted and the configured fallback
 * (local) serves the chunk, prefixed by a Reset so nothing is duplicated. Also proves the Lua-based budget
 * never overshoots under concurrency.
 */
@CodelensIntegrationTest
@TestPropertySource(properties = {
        "codelens.llm.provider=claude",
        "codelens.llm.fallback-provider=local",
        "codelens.llm.claude.api-key=test-key",
        "codelens.llm.claude.base-url=http://127.0.0.1:9",
        "codelens.llm.claude.timeout-seconds=2",
        "codelens.llm.budget.daily-tokens=50000000",
        "resilience4j.retry.instances.llm.waitDuration=10ms"
})
class BudgetAndFallbackIT {

    @Autowired
    AIReviewService aiReviewService;
    @Autowired
    TokenBudgetService budget;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MeterRegistry meters;

    @Test
    void deadPrimaryFallsBackToLocalWithAReset() {
        ReviewPromptContext ctx = ReviewPromptContext.of(new DiffChunk("Dao.java", 1,
                "@@ -1,1 +1,2 @@\n+String sql = \"SELECT * FROM t WHERE x = '\" + q + \"'\";\n", "java"), "system");
        double before = fallbackCount();
        List<ReviewEvent> events = aiReviewService.stream(ctx).collectList().block(Duration.ofSeconds(60));
        assertThat(events).isNotNull();
        assertThat(events).contains(new ReviewEvent.Reset("fallback"));
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(ReviewEvent.Finding.class,
                f -> assertThat(f.ruleKey()).isEqualTo("local.sql-concat")));
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(ReviewEvent.Usage.class,
                u -> assertThat(u.providerId()).isEqualTo("local")));
        assertThat(fallbackCount()).isGreaterThan(before);
    }

    private double fallbackCount() {
        return meters.find("llm.fallback").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void concurrentReservationsNeverExceedTheBudget() throws Exception {
        String key = "rate:llm:tokens:" + java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        redis.delete(key);
        redis.opsForValue().set(key, String.valueOf(50_000_000L - 10_000L)); // 10k tokens of headroom
        AtomicInteger granted = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        budget.reserve(100, false);
                        granted.incrementAndGet();
                    } catch (BudgetExceededException expected) {
                        // over budget: refused atomically
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(granted.get()).isEqualTo(100);
        assertThat(budget.usedToday()).isEqualTo(50_000_000L);
        TokenBudgetService.Reservation r = new TokenBudgetService.Reservation(key, 100, true);
        budget.reconcile(r, 40);
        assertThat(budget.usedToday()).isEqualTo(50_000_000L - 60);
        redis.delete(key);
    }

    @Test
    void freeModelsAreNotCharged() {
        String key = "rate:llm:tokens:" + java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        long before = budget.usedToday();
        assertThat(budget.reserve(999_999_999L, true).charged()).isFalse();
        assertThat(redis.opsForValue().get(key) == null ? 0 : budget.usedToday()).isEqualTo(before);
    }
}
