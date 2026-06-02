package com.codelensai.service;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewToken;
import com.codelensai.service.llm.LlmReviewProvider;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Streams a single diff chunk through the active {@link LlmReviewProvider}, wrapped in Resilience4j.
 * One bad chunk degrades to an empty stream (chunk-level isolation) rather than failing the whole review;
 * on a total provider outage the circuit opens and the fallback fires. An outbound Redis sliding-window
 * rate limit bounds calls across pods.
 */
@Service
public class AIReviewService {

    private static final Logger log = LoggerFactory.getLogger(AIReviewService.class);
    private static final String LLM_RATE_KEY = "rate:llm:global";
    private static final int LLM_RATE_LIMIT = 600;
    private static final Duration LLM_RATE_WINDOW = Duration.ofMinutes(1);

    private final LlmReviewProvider provider;
    private final PromptRepository prompts;
    private final RateLimiterService rateLimiter;

    public AIReviewService(LlmReviewProvider provider, PromptRepository prompts, RateLimiterService rateLimiter) {
        this.provider = provider;
        this.prompts = prompts;
        this.rateLimiter = rateLimiter;
    }

    @CircuitBreaker(name = "llm", fallbackMethod = "fallback")
    @Retry(name = "llm")
    @Bulkhead(name = "llm")
    public Flux<ReviewToken> stream(DiffChunk chunk) {
        if (!rateLimiter.allow(LLM_RATE_KEY, LLM_RATE_LIMIT, LLM_RATE_WINDOW)) {
            log.warn("Outbound LLM rate limit hit; shedding chunk {}", chunk == null ? "?" : chunk.fileName());
            return Flux.empty();
        }
        ReviewPromptContext ctx = ReviewPromptContext.of(chunk, prompts.reviewPrompt());
        return provider.streamReview(ctx);
    }

    @SuppressWarnings("unused") // referenced by Resilience4j as the circuit-breaker fallback
    private Flux<ReviewToken> fallback(DiffChunk chunk, Throwable t) {
        log.warn("LLM review unavailable for {} — degrading to no comments for this chunk: {}",
                chunk == null ? "?" : chunk.fileName(), t.getMessage());
        return Flux.empty();
    }

    public String providerId() {
        return provider.providerId();
    }
}
