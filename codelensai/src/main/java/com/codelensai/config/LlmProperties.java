package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config-driven LLM settings bound from {@code codelens.llm.*}. The model is config, not code. */
@ConfigurationProperties(prefix = "codelens.llm")
public record LlmProperties(
        String provider,
        String reviewModel,
        String summaryModel,
        Double temperature,
        Integer maxOutputTokens,
        Integer maxTokensPerChunk,
        Double confidenceThreshold,
        String fallbackProvider,
        Long dailyTokenBudget
) {
    public LlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = "local";
        }
        if (maxTokensPerChunk == null || maxTokensPerChunk <= 0) {
            maxTokensPerChunk = 4000;
        }
        if (confidenceThreshold == null) {
            confidenceThreshold = 0.0;
        }
    }
}
