package com.codelensai.util;

/** Shared Redis Streams keys so producer and consumer never drift. */
public final class StreamKeys {

    private StreamKeys() {
    }

    public static final String REVIEW_JOBS = "review-jobs";
    public static final String REVIEW_GROUP = "reviewers";
    public static final String REVIEW_DLQ = "review-jobs-dlq";

    /** Per-PR Redis Pub/Sub channel prefix for cross-pod WebSocket fan-out. */
    public static final String REVIEW_CHANNEL_PREFIX = "review:";
}
