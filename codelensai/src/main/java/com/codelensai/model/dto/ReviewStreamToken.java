package com.codelensai.model.dto;

import com.codelensai.model.enums.Severity;

/**
 * One streamed text fragment of a finding, pushed to {@code /topic/pr/{id}/review} as the model emits it.
 * {@code key} is the finding's fingerprint (the client groups fragments by it); {@code chunk} lets a
 * {@code reset} discard just that chunk's text. {@code seq} is monotonic per review session
 * ({@code sessionId}), which makes the live stream resumable (see {@code ReviewStreamBuffer}).
 * {@code confidence} and {@code ruleKey} ride on the first fragment of each finding.
 */
public record ReviewStreamToken(long sessionId, long seq, int chunk, String key, String file, int line,
                                Severity severity, String text, Double confidence, String ruleKey)
        implements ReviewTopicMessage {
}
