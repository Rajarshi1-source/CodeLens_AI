package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Daily LLM token budget ({@code codelens.llm.budget.*}). Enforced atomically in Redis across pods.
 * Free models (price 0 - the local mock) are not counted against it unless
 * {@code enforce-for-free-models=true}, so eval/demo/CI runs can never be blocked by it.
 */
@ConfigurationProperties(prefix = "codelens.llm.budget")
public record BudgetProperties(Long dailyTokens, Boolean enforceForFreeModels) {
    public BudgetProperties {
        if (dailyTokens == null || dailyTokens <= 0) {
            dailyTokens = 2_000_000L;
        }
        if (enforceForFreeModels == null) {
            enforceForFreeModels = Boolean.FALSE;
        }
    }
}
