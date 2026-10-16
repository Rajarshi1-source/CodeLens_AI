package com.codelensai.model.dto;

/**
 * Body of {@code /topic/pr/{id}/status}. {@code sessionId} tells the client whether this is the review it
 * is already showing or a new one (re-review): a new session resets its stream state. {@code reason} is
 * set for failures (e.g. {@code BUDGET_EXCEEDED}).
 */
public record ReviewStatusMessage(long sessionId, String status, String reason) {
}
