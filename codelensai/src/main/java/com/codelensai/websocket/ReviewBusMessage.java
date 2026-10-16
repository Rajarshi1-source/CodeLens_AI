package com.codelensai.websocket;

/**
 * Envelope published on the Redis channel {@code review:{prId}} so any pod can relay the event to its
 * locally-connected STOMP clients. {@code topic} is the per-PR destination suffix ({@code review} or
 * {@code status}); {@code payload} is the already-serialized JSON body, relayed verbatim.
 */
public record ReviewBusMessage(String topic, String payload) {

    public static final String REVIEW = "review";
    public static final String STATUS = "status";
}
