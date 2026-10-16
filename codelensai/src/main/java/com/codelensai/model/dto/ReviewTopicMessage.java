package com.codelensai.model.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Everything pushed to {@code /topic/pr/{id}/review}, as a discriminated union on {@code type}:
 * {@code token} ({@link ReviewStreamToken}), {@code reset} (discard a chunk's streamed text — a retry or
 * fallback restarted it) and {@code error} (one chunk failed). All carry the review {@code sessionId} and a
 * per-session {@code seq}, so a reconnecting client can replay exactly what it missed and a re-review
 * (new session) resets the client's sequence instead of being dropped as "old".
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReviewStreamToken.class, name = "token"),
        @JsonSubTypes.Type(value = ReviewTopicMessage.Reset.class, name = "reset"),
        @JsonSubTypes.Type(value = ReviewTopicMessage.ChunkError.class, name = "error")
})
public sealed interface ReviewTopicMessage permits ReviewStreamToken, ReviewTopicMessage.Reset, ReviewTopicMessage.ChunkError {

    long sessionId();

    long seq();

    record Reset(long sessionId, long seq, int chunk) implements ReviewTopicMessage {
    }

    record ChunkError(long sessionId, long seq, int chunk, String file) implements ReviewTopicMessage {
    }
}
