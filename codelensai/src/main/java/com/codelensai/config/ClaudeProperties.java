package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic Claude provider settings ({@code codelens.llm.claude.*}). Claude Opus 5.5 always thinks, so
 * there is deliberately no temperature or thinking knob here: depth is controlled with {@code effort}, and
 * {@code max-tokens} must leave room for thinking plus the JSON answer.
 */
@ConfigurationProperties(prefix = "codelens.llm.claude")
public record ClaudeProperties(
        String apiKey,
        String baseUrl,
        String reviewModel,
        String summaryModel,
        String effort,
        Integer maxTokens,
        Integer summaryMaxTokens,
        Boolean serverFallback,
        Integer timeoutSeconds
) {
    public ClaudeProperties {
        if (reviewModel == null || reviewModel.isBlank()) {
            reviewModel = "claude-opus-5-5";
        }
        if (summaryModel == null || summaryModel.isBlank()) {
            summaryModel = "claude-opus-5-5";
        }
        if (effort == null || effort.isBlank()) {
            effort = "high";
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 16000;
        }
        if (summaryMaxTokens == null || summaryMaxTokens <= 0) {
            summaryMaxTokens = 4000;
        }
        if (serverFallback == null) {
            serverFallback = Boolean.TRUE;
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 180;
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
