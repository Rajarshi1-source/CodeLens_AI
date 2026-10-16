package com.codelensai.util;

/** Shared Redis Streams keys so producer and consumer never drift. */
public final class StreamKeys {

    private StreamKeys() {
    }

    public static final String REVIEW_JOBS = "review-jobs";
    public static final String REVIEW_GROUP = "reviewers";
    public static final String REVIEW_DLQ = "review-jobs-dlq";

    /** G1 repository indexing jobs (full re-index on connect, changed paths after a merge). */
    public static final String INDEX_JOBS = "index-jobs";
    public static final String INDEX_GROUP = "indexers";
    public static final String INDEX_DLQ = "index-jobs-dlq";

    /** Per-PR Redis Pub/Sub channel prefix for cross-pod WebSocket fan-out. */
    public static final String REVIEW_CHANNEL_PREFIX = "review:";
    /** Per-PR Redis Pub/Sub channel prefix for collaborative presence (G5). */
    public static final String PRESENCE_CHANNEL_PREFIX = "presence-events:";
}
