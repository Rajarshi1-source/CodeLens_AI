package com.codelensai.websocket;

import com.codelensai.model.dto.ReviewStreamToken;

/**
 * Envelope published on the Redis channel {@code review:{prId}} so any pod can relay the event to
 * its locally-connected STOMP clients. {@code kind} routes it to the right per-PR topic.
 */
public record ReviewBusMessage(String kind, ReviewStreamToken token, String status, String errorFile) {

    public static final String TOKEN = "TOKEN";
    public static final String STATUS = "STATUS";
    public static final String ERROR = "ERROR";

    public static ReviewBusMessage token(ReviewStreamToken token) {
        return new ReviewBusMessage(TOKEN, token, null, null);
    }

    public static ReviewBusMessage status(String status) {
        return new ReviewBusMessage(STATUS, null, status, null);
    }

    public static ReviewBusMessage error(String file) {
        return new ReviewBusMessage(ERROR, null, null, file);
    }
}
