package com.codelensai.model.dto;

import com.codelensai.model.enums.Severity;

/**
 * One streamed fragment pushed over WebSocket to {@code /topic/pr/{id}/review} as the LLM emits tokens.
 * {@code seq} is a per-session monotonic sequence number that makes the live stream resumable:
 * a reconnecting client replays everything with a higher seq than it last saw.
 */
public record ReviewStreamToken(long seq, String file, int line, Severity severity, String text) {

    public static ReviewStreamToken of(long seq, String file, int line, Severity severity, String text) {
        return new ReviewStreamToken(seq, file, line, severity, text);
    }
}
