package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** G3 risk-score thresholds and the sensitive-path patterns ({@code codelens.risk.*}). */
@ConfigurationProperties(prefix = "codelens.risk")
public record RiskProperties(Integer highThreshold, Integer mediumThreshold, List<String> sensitivePaths) {
    public RiskProperties {
        if (highThreshold == null) {
            highThreshold = 60;
        }
        if (mediumThreshold == null) {
            mediumThreshold = 30;
        }
        if (sensitivePaths == null || sensitivePaths.isEmpty()) {
            sensitivePaths = List.of(
                    "(?i)(^|/)(auth|security|crypto|payments?|billing|login|session|oauth|secrets?)(/|$)",
                    "(?i)(^|/)[^/]*(auth|security|crypto|payment|billing|login|password|token|secret)[^/]*$",
                    "(?i)(^|/)(db/)?migrations?/",
                    "(?i)\\.sql$",
                    "(?i)(^|/)application[^/]*\\.(ya?ml|properties)$",
                    "(?i)(^|/)Dockerfile$",
                    "(?i)(^|/)\\.github/workflows/");
        }
    }
}
