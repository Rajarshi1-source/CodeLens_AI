package com.codelensai.service.llm;

import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewToken;
import reactor.core.publisher.Flux;

/**
 * Config-driven LLM adapter. The model is NEVER hardcoded in a service — services depend on this
 * sealed interface and the concrete bean is chosen in {@code LlmConfig} from {@code codelens.llm.provider}.
 *
 * <p>Sealed so any exhaustive switch over a provider (e.g. fallback dispatch) fails to compile
 * until a newly added provider is handled.
 */
public sealed interface LlmReviewProvider
        permits OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider {

    /** Streams structured review tokens for one diff chunk. */
    Flux<ReviewToken> streamReview(ReviewPromptContext ctx);

    /** Stable id used in logs and {@code review_sessions.model_used}. */
    String providerId();
}
