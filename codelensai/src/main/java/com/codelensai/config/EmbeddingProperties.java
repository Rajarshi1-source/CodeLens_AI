package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding adapter settings ({@code codelens.embedding.*}). {@code hashing} (default) is an offline,
 * deterministic feature-hashing embedder so CI and evals need no key; {@code openai} uses
 * {@code text-embedding-3-small} reduced to the same 512 dimensions the {@code vector(512)} column fixes.
 */
@ConfigurationProperties(prefix = "codelens.embedding")
public record EmbeddingProperties(String provider, Integer dimensions, String openaiModel,
                                  String openaiApiKey, String openaiBaseUrl, Integer batchSize) {
    public static final int SCHEMA_DIMENSIONS = 512;

    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "hashing";
        }
        if (dimensions == null || dimensions <= 0) {
            dimensions = SCHEMA_DIMENSIONS;
        }
        if (openaiModel == null || openaiModel.isBlank()) {
            openaiModel = "text-embedding-3-small";
        }
        if (openaiBaseUrl == null || openaiBaseUrl.isBlank()) {
            openaiBaseUrl = "https://api.openai.com/v1";
        }
        if (batchSize == null || batchSize <= 0) {
            batchSize = 64;
        }
    }
}
