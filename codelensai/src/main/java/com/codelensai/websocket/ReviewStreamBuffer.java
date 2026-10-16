package com.codelensai.websocket;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Durable short-term buffer behind the resumable live stream (master plan §9.5). Every message published
 * to a PR's review topic is also appended to a capped Redis Stream {@code review:tokens:{prId}}
 * ({@code MAXLEN ~ 5000}, 2h TTL), together with the current session id and status. A reconnecting client
 * asks for everything after the last {@code seq} it saw and gets exactly the gap — Pub/Sub alone is
 * fire-and-forget and would lose it.
 */
@Component
public class ReviewStreamBuffer {

    static final String PREFIX = "review:tokens:";
    private static final Duration TTL = Duration.ofHours(2);
    private static final long MAX_LEN = 5000;

    private final StringRedisTemplate redis;

    public ReviewStreamBuffer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Snapshot(long sessionId, String status, String reason, List<String> items) {
    }

    /** A new review session starts: drop the previous session's buffer. */
    public void reset(long prId, long sessionId, String status) {
        redis.delete(streamKey(prId));
        redis.opsForValue().set(sessionKey(prId), String.valueOf(sessionId), TTL);
        setStatus(prId, status, null);
    }

    public void append(long prId, long seq, String json) {
        String key = streamKey(prId);
        RecordId id = redis.opsForStream().add(StreamRecords.mapBacked(Map.of("seq", String.valueOf(seq), "json", json))
                .withStreamKey(key));
        if (id != null) {
            redis.opsForStream().trim(key, MAX_LEN, true);
            redis.expire(key, TTL);
        }
    }

    public void setStatus(long prId, String status, String reason) {
        redis.opsForValue().set(statusKey(prId), status + "|" + (reason == null ? "" : reason), TTL);
    }

    /** Everything after {@code afterSeq} for the current session (all of it if the client's session is stale). */
    public Snapshot after(long prId, long afterSeq, long clientSessionId) {
        long session = currentSession(prId);
        long from = session != clientSessionId ? 0 : afterSeq;
        List<String> items = new ArrayList<>();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(streamKey(prId), Range.unbounded());
        if (records != null) {
            for (MapRecord<String, Object, Object> r : records) {
                long seq = parse(r.getValue().get("seq"));
                if (seq > from) {
                    items.add(String.valueOf(r.getValue().get("json")));
                }
            }
        }
        String[] status = statusOf(prId);
        return new Snapshot(session, status[0], status[1].isBlank() ? null : status[1], items);
    }

    public long currentSession(long prId) {
        return parse(redis.opsForValue().get(sessionKey(prId)));
    }

    private String[] statusOf(long prId) {
        String raw = redis.opsForValue().get(statusKey(prId));
        if (raw == null) {
            return new String[]{null, ""};
        }
        int bar = raw.indexOf('|');
        return bar < 0 ? new String[]{raw, ""} : new String[]{raw.substring(0, bar), raw.substring(bar + 1)};
    }

    private static long parse(Object v) {
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String streamKey(long prId) {
        return PREFIX + prId;
    }

    private static String sessionKey(long prId) {
        return PREFIX + prId + ":session";
    }

    private static String statusKey(long prId) {
        return PREFIX + prId + ":status";
    }
}
