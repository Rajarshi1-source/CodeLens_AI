package com.codelensai;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewToken;
import com.codelensai.service.AIReviewService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Spring Boot 4 migration: proves the Resilience4j aspects are actually
 * woven around {@link AIReviewService}.
 *
 * <p>Boot 4 removed {@code spring-boot-starter-aop}, and {@code resilience4j-spring-boot4} does not
 * pull an AOP starter transitively the way {@code resilience4j-spring-boot3} did. If
 * {@code spring-boot-starter-aspectj} is ever dropped from {@code pom.xml}, the
 * {@code @CircuitBreaker} / {@code @Retry} / {@code @Bulkhead} annotations become silent no-ops:
 * the application still starts, every other test still passes, and the circuit breaker, retries and
 * bulkhead simply stop existing. That failure mode is invisible to every other test in this
 * repository, which is why it gets its own.
 *
 * <p>The proof is that a call routed through {@code AIReviewService.stream(...)} is <em>recorded by
 * the Resilience4j registries</em>. Only the aspect can record it, so a non-zero delta means the
 * advice ran. A successful call is used rather than a forced failure because
 * {@code LlmReviewProvider} is a sealed interface and cannot be mocked.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ResilienceAspectsTest {

    @Autowired
    AIReviewService aiReviewService;
    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    RetryRegistry retryRegistry;

    @Test
    void circuitBreakerAspectIsWovenAroundStream() {
        CircuitBreaker llm = circuitBreakerRegistry.circuitBreaker("llm");
        int before = llm.getMetrics().getNumberOfBufferedCalls();

        List<ReviewToken> tokens = aiReviewService.stream(chunk()).collectList().block(Duration.ofSeconds(30));

        assertNotNull(tokens, "stream() returned no result at all");
        int after = llm.getMetrics().getNumberOfBufferedCalls();
        assertTrue(after > before,
                "CircuitBreaker 'llm' recorded no call (" + before + " -> " + after + "), so the "
                        + "@CircuitBreaker aspect is NOT woven. Check that spring-boot-starter-aspectj "
                        + "is on the classpath -- Boot 4 removed spring-boot-starter-aop.");
    }

    @Test
    void retryAspectIsWovenAroundStream() {
        var llm = retryRegistry.retry("llm");
        long before = llm.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt();

        aiReviewService.stream(chunk()).collectList().block(Duration.ofSeconds(30));

        long after = llm.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt();
        assertTrue(after > before,
                "Retry 'llm' recorded no call (" + before + " -> " + after + "), so the @Retry "
                        + "aspect is NOT woven.");
    }

    @Test
    void configuredInstancesAreRegistered() {
        assertNotNull(circuitBreakerRegistry.circuitBreaker("llm"));
        assertNotNull(circuitBreakerRegistry.circuitBreaker("github"));
        assertNotNull(retryRegistry.retry("llm"));
        assertNotNull(retryRegistry.retry("github"));
    }

    private static DiffChunk chunk() {
        return new DiffChunk("Example.java", 1, "+    int answer = 42;", "java");
    }
}
