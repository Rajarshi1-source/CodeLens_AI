package com.codelensai.service;

import com.codelensai.config.LlmProperties;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.SummaryContext;
import com.codelensai.model.dto.SummaryResult;
import com.codelensai.service.cost.BudgetExceededException;
import com.codelensai.service.cost.ModelPricing;
import com.codelensai.service.cost.TokenBudgetService;
import com.codelensai.service.llm.LlmProviderRegistry;
import com.codelensai.service.llm.LlmRefusalException;
import com.codelensai.service.llm.LlmReviewProvider;
import com.codelensai.service.llm.LocalModelProvider;
import com.codelensai.service.llm.ReviewPromptRenderer;
import com.codelensai.service.llm.SummaryPrompt;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams one diff chunk through the primary {@link LlmReviewProvider}, wrapped in Resilience4j.
 *
 * <ul>
 *   <li><b>Retry → fallback provider.</b> Transient failures are retried; when retries are exhausted (or the
 *       circuit is open) the configured {@code fallback-provider} takes over. Every subscription starts with
 *       {@link ReviewEvent.Reset}, so a retry or fallback after partial output never duplicates findings.</li>
 *   <li><b>Refusals and budget</b> are not outages: both are excluded from the breaker and retry
 *       ({@code ignoreExceptions}). A refusal goes straight to the fallback provider; an exhausted budget
 *       fails the chunk.</li>
 *   <li><b>Daily token budget.</b> Each call reserves its estimated tokens atomically in Redis and
 *       reconciles against the real {@link ReviewEvent.Usage}; free models are not charged.</li>
 *   <li><b>Rate limit.</b> A cross-pod sliding window sheds excess calls (counted, degrade to no comments).</li>
 * </ul>
 */
@Service
public class AIReviewService {

    private static final Logger log = LoggerFactory.getLogger(AIReviewService.class);
    private static final String LLM_RATE_KEY = "rate:llm:global";
    private static final int LLM_RATE_LIMIT = 600;
    private static final Duration LLM_RATE_WINDOW = Duration.ofMinutes(1);

    private final LlmProviderRegistry registry;
    private final RateLimiterService rateLimiter;
    private final TokenBudgetService budget;
    private final ModelPricing pricing;
    private final LlmProperties llmProperties;
    private final MeterRegistry meters;

    public AIReviewService(LlmProviderRegistry registry, RateLimiterService rateLimiter, TokenBudgetService budget,
                           ModelPricing pricing, LlmProperties llmProperties, MeterRegistry meters) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.pricing = pricing;
        this.llmProperties = llmProperties;
        this.meters = meters;
    }

    @Retry(name = "llm", fallbackMethod = "fallback")
    @CircuitBreaker(name = "llm")
    @Bulkhead(name = "llm")
    public Flux<ReviewEvent> stream(ReviewPromptContext ctx) {
        return guarded(registry.primary(), ctx, "subscribe");
    }

    @SuppressWarnings("unused") // Resilience4j fallback: the most specific matching signature wins
    private Flux<ReviewEvent> fallback(ReviewPromptContext ctx, BudgetExceededException e) {
        return Flux.error(e);
    }

    @SuppressWarnings("unused") // Resilience4j fallback for every other failure
    private Flux<ReviewEvent> fallback(ReviewPromptContext ctx, Throwable t) {
        LlmReviewProvider primary = registry.primary();
        String reason = t instanceof LlmRefusalException ? "refusal" : t.getClass().getSimpleName();
        if (t instanceof LlmRefusalException refusal) {
            meters.counter("llm.refusals", "provider", primary.providerId(),
                    "category", String.valueOf(refusal.category())).increment();
        }
        return registry.fallbackFor(primary)
                .map(fb -> {
                    log.warn("LLM {} failed for {} ({}): falling back to {}", primary.providerId(),
                            fileOf(ctx), t.getMessage(), fb.providerId());
                    meters.counter("llm.fallback", "from", primary.providerId(), "to", fb.providerId(),
                            "reason", reason).increment();
                    return guarded(fb, ctx, "fallback");
                })
                .orElseGet(() -> {
                    log.warn("LLM review unavailable for {} and no fallback configured: {}", fileOf(ctx), t.getMessage());
                    return Flux.error(t);
                });
    }

    /** One budgeted, rate-limited call; deferred so every (re)subscription re-reserves and re-resets. */
    private Flux<ReviewEvent> guarded(LlmReviewProvider provider, ReviewPromptContext ctx, String resetReason) {
        return Flux.defer(() -> {
            if (!rateLimiter.allow(LLM_RATE_KEY, LLM_RATE_LIMIT, LLM_RATE_WINDOW)) {
                log.warn("Outbound LLM rate limit hit; shedding chunk {}", fileOf(ctx));
                meters.counter("llm.shed").increment();
                return Flux.just(new ReviewEvent.Reset(resetReason));
            }
            long estimate = ReviewPromptRenderer.estimateTokens(ReviewPromptRenderer.userPrompt(ctx, true))
                    + llmProperties.maxOutputTokens();
            TokenBudgetService.Reservation reservation = budget.reserve(estimate, pricing.isFree(provider.reviewModel()));
            AtomicBoolean usageSeen = new AtomicBoolean();
            return Flux.concat(Flux.just(new ReviewEvent.Reset(resetReason)), provider.streamReview(ctx))
                    .doOnNext(e -> {
                        if (e instanceof ReviewEvent.Usage u && usageSeen.compareAndSet(false, true)) {
                            budget.reconcile(reservation, u.total());
                        }
                    })
                    .doOnError(err -> {
                        if (!usageSeen.get()) {
                            budget.release(reservation);
                        }
                    })
                    .doOnCancel(() -> {
                        if (!usageSeen.get()) {
                            budget.release(reservation);
                        }
                    });
        });
    }

    @Retry(name = "llm", fallbackMethod = "summaryFallback")
    @CircuitBreaker(name = "llm")
    public Mono<SummaryResult> summarize(SummaryContext ctx) {
        LlmReviewProvider provider = registry.primary();
        return Mono.defer(() -> {
            long estimate = ReviewPromptRenderer.estimateTokens(SummaryPrompt.render(ctx)) + 1000;
            TokenBudgetService.Reservation reservation = budget.reserve(estimate, pricing.isFree(provider.reviewModel()));
            return provider.summarize(ctx)
                    .doOnNext(r -> budget.reconcile(reservation, r.usage() == null ? 0 : r.usage().total()))
                    .doOnError(err -> budget.release(reservation));
        });
    }

    @SuppressWarnings("unused") // the summary is prose: on any failure degrade to the deterministic template
    private Mono<SummaryResult> summaryFallback(SummaryContext ctx, Throwable t) {
        log.info("Summary model unavailable ({}); using the template summary", t.getMessage());
        return Mono.just(new SummaryResult(LocalModelProvider.templateSummary(ctx), null));
    }

    public String providerId() {
        return registry.primary().providerId();
    }

    public String reviewModel() {
        return registry.primary().reviewModel();
    }

    private static String fileOf(ReviewPromptContext ctx) {
        return ctx == null || ctx.chunk() == null ? "?" : ctx.chunk().fileName();
    }
}
