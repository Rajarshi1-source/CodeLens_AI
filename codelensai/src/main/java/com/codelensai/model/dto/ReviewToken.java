package com.codelensai.model.dto;

import com.codelensai.model.enums.Severity;

/**
 * One structured fragment emitted by an {@code LlmReviewProvider} as it streams a chunk review.
 * The provider's job is to translate its SSE stream into a {@code Flux<ReviewToken>}.
 */
public record ReviewToken(String file, int line, Severity severity, String text) {
}
