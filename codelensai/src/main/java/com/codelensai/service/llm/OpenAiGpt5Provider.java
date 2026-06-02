package com.codelensai.service.llm;

import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewToken;
import reactor.core.publisher.Flux;

/**
 * Real OpenAI (GPT-5-class) provider. Stub for now: the MVP runs on {@link LocalModelProvider}.
 * To enable, implement the WebClient SSE call here and select it via {@code codelens.llm.provider=gpt5}.
 */
public final class OpenAiGpt5Provider implements LlmReviewProvider {

    @Override
    public Flux<ReviewToken> streamReview(ReviewPromptContext ctx) {
        return Flux.error(new UnsupportedOperationException(
                "OpenAiGpt5Provider not yet implemented — set codelens.llm.provider=local for the MVP"));
    }

    @Override
    public String providerId() {
        return "gpt5";
    }
}
