package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** G1 repo-aware retrieval knobs ({@code codelens.retrieval.*}). */
@ConfigurationProperties(prefix = "codelens.retrieval")
public record RetrievalProperties(Boolean enabled, Integer maxContextTokens, Integer topK,
                                  Integer maxSnippetLines, Integer maxCallers, Integer maxTests) {
    public RetrievalProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (maxContextTokens == null || maxContextTokens <= 0) {
            maxContextTokens = 1500;
        }
        if (topK == null || topK <= 0) {
            topK = 4;
        }
        if (maxSnippetLines == null || maxSnippetLines <= 0) {
            maxSnippetLines = 40;
        }
        if (maxCallers == null || maxCallers <= 0) {
            maxCallers = 6;
        }
        if (maxTests == null || maxTests <= 0) {
            maxTests = 2;
        }
    }
}
