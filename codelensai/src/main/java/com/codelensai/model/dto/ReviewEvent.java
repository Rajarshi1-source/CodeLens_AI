package com.codelensai.model.dto;

import com.codelensai.model.enums.Severity;

/**
 * What an {@code LlmReviewProvider} streams for one diff chunk. Sealed so the consumer's {@code switch}
 * is exhaustive (no {@code default}): adding a variant fails to compile until every consumer handles it.
 *
 * <ul>
 *   <li>{@link Reset} — discard this chunk's findings so far. Emitted at the start of every (re)subscription
 *       and when a fallback (cross-provider or Claude's server-side refusal fallback) takes over mid-stream,
 *       so a Resilience4j retry of a partially-streamed {@code Flux} never duplicates findings.</li>
 *   <li>{@link Finding} — one complete, schema-validated comment.</li>
 *   <li>{@link Usage} — token accounting for the call (estimated for the local provider).</li>
 * </ul>
 */
public sealed interface ReviewEvent {

    record Reset(String reason) implements ReviewEvent {
    }

    record Finding(String file, int line, Severity severity, String text,
                   String codeSuggestion, double confidence, String ruleKey) implements ReviewEvent {
        public Finding {
            if (severity == null) {
                severity = Severity.SUGGESTION;
            }
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            if (ruleKey == null || ruleKey.isBlank()) {
                ruleKey = "llm.general";
            }
            if (codeSuggestion != null && codeSuggestion.isBlank()) {
                codeSuggestion = null;
            }
        }
    }

    record Usage(String providerId, String model, long inputTokens, long outputTokens,
                 boolean estimated) implements ReviewEvent {
        public long total() {
            return inputTokens + outputTokens;
        }
    }
}
