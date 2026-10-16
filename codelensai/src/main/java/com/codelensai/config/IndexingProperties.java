package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Repository indexing limits ({@code codelens.indexing.*}). */
@ConfigurationProperties(prefix = "codelens.indexing")
public record IndexingProperties(Boolean enabled, Integer maxFiles, Integer maxFileBytes,
                                 Integer fetchConcurrency, List<String> extensions) {
    public IndexingProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (maxFiles == null || maxFiles <= 0) {
            maxFiles = 2000;
        }
        if (maxFileBytes == null || maxFileBytes <= 0) {
            maxFileBytes = 200_000;
        }
        if (fetchConcurrency == null || fetchConcurrency <= 0) {
            fetchConcurrency = 8;
        }
        if (extensions == null || extensions.isEmpty()) {
            extensions = List.of("java", "kt", "py", "js", "jsx", "ts", "tsx", "go", "rb", "rs", "cs",
                    "c", "h", "cpp", "cc", "hpp");
        }
    }
}
