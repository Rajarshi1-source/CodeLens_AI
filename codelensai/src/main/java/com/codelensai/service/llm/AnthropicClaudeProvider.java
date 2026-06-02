package com.codelensai.service.llm;

import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewToken;
import reactor.core.publisher.Flux;

/**
 * Real Anthropic Claude provider. Stub for now: the MVP runs on {@link LocalModelProvider}.
 * To enable, implement the WebClient SSE call here and select it via {@code codelens.llm.provider=claude}.
 */
public final class AnthropicClaudeProvider implements LlmReviewProvider {

    @Override
    public Flux<ReviewToken> streamReview(ReviewPromptContext ctx) {
        return Flux.error(new UnsupportedOperationException(
                "AnthropicClaudeProvider not yet implemented — set codelens.llm.provider=local for the MVP"));
    }

    @Override
    public String providerId() {
        return "claude";
    }
}
