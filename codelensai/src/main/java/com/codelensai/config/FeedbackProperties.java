package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** G7 feedback-driven suppression thresholds ({@code codelens.feedback.*}). */
@ConfigurationProperties(prefix = "codelens.feedback")
public record FeedbackProperties(Boolean suppressionEnabled, Integer minVotes, Double dismissRate) {
    public FeedbackProperties {
        if (suppressionEnabled == null) {
            suppressionEnabled = Boolean.TRUE;
        }
        if (minVotes == null || minVotes <= 0) {
            minVotes = 5;
        }
        if (dismissRate == null) {
            dismissRate = 0.7;
        }
    }
}
