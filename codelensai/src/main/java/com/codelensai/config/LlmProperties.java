package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config-driven LLM settings bound from {@code codelens.llm.*}. The model is config, not code. The
 * {@code review-model}/{@code summary-model}/{@code openai-*} keys configure the OpenAI provider; Claude,
 * pricing and the daily budget have their own records ({@code codelens.llm.claude|pricing|budget}).
 */
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
        String openaiApiKey,
        String openaiBaseUrl
) {
    public LlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = "local";
        }
        if (reviewModel == null || reviewModel.isBlank()) {
            reviewModel = "local-mock";
        }
        if (temperature == null) {
            temperature = 0.1;
        }
        if (maxOutputTokens == null || maxOutputTokens <= 0) {
            maxOutputTokens = 2000;
        }
        if (maxTokensPerChunk == null || maxTokensPerChunk <= 0) {
            maxTokensPerChunk = 4000;
        }
        if (confidenceThreshold == null) {
            confidenceThreshold = 0.0;
        }
        if (openaiBaseUrl == null || openaiBaseUrl.isBlank()) {
            openaiBaseUrl = "https://api.openai.com/v1";
        }
    }
}
