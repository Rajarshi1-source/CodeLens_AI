package com.codelensai.service.llm;

import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.SummaryContext;
import com.codelensai.model.dto.SummaryResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Config-driven LLM adapter. The model is NEVER hardcoded in a service — services depend on this
 * sealed interface and the concrete bean is chosen in {@code LlmConfig} from {@code codelens.llm.provider}.
 *
 * <p>Sealed so any exhaustive switch over a provider (e.g. {@link LlmProviderRegistry#idOf}) fails to
 * compile until a newly added provider is handled.
 */
public sealed interface LlmReviewProvider
        permits OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider {

    /**
     * Streams review events for one diff chunk: {@code Finding}s as each comment completes, then a final
     * {@code Usage}. Providers do not emit the leading {@code Reset}; {@code AIReviewService} adds it.
     */
    Flux<ReviewEvent> streamReview(ReviewPromptContext ctx);

    /** PR-level prose summary (G3) on the provider's (usually cheaper) summary model. */
    Mono<SummaryResult> summarize(SummaryContext ctx);

    /** Stable id used in logs, metrics and the fallback registry. */
    String providerId();

    /** The model this provider reviews with (recorded as {@code provider:model} on the session). */
    String reviewModel();
}
