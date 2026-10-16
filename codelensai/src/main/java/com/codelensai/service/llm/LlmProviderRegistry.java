package com.codelensai.service.llm;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the primary provider and its configured cross-vendor fallback. Built by {@code LlmConfig}.
 * {@link #idOf} switches exhaustively over the sealed provider type with no {@code default}, so adding a
 * provider is a compile error here until it is given an id.
 */
public final class LlmProviderRegistry {

    private final Map<String, LlmReviewProvider> byId;
    private final LlmReviewProvider primary;
    private final String fallbackId;

    public LlmProviderRegistry(Map<String, LlmReviewProvider> byId, String primaryId, String fallbackId) {
        this.byId = Map.copyOf(byId);
        this.primary = resolve(primaryId);
        this.fallbackId = fallbackId == null ? "" : fallbackId.trim();
    }

    public static String idOf(LlmReviewProvider provider) {
        return switch (provider) {
            case LocalModelProvider l -> "local";
            case OpenAiGpt5Provider o -> "gpt5";
            case AnthropicClaudeProvider a -> "claude";
        };
    }

    public LlmReviewProvider primary() {
        return primary;
    }

    /** The configured fallback, unless unset or the same provider (no point failing over to itself). */
    public Optional<LlmReviewProvider> fallbackFor(LlmReviewProvider provider) {
        if (fallbackId.isBlank() || fallbackId.equals(idOf(provider))) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(fallbackId));
    }

    public LlmReviewProvider resolve(String id) {
        LlmReviewProvider p = byId.get(id);
        if (p == null) {
            throw new IllegalStateException("Unknown LLM provider: " + id + " (expected one of " + byId.keySet() + ")");
        }
        return p;
    }
}
