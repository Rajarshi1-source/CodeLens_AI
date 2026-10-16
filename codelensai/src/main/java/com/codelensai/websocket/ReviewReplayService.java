package com.codelensai.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the catch-up batch a (re)connecting client needs: every buffered review-topic message after its
 * last {@code seq} for the current session, plus the current status. If the client's session is stale (a
 * re-review started meanwhile) it gets the new session from the beginning.
 */
@Service
public class ReviewReplayService {

    private final ReviewStreamBuffer buffer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public ReviewReplayService(ReviewStreamBuffer buffer, ObjectMapper objectMapper, MeterRegistry meters) {
        this.buffer = buffer;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    public record ReplayBatch(long sessionId, String status, String reason, List<JsonNode> items) {
    }

    public ReplayBatch replay(long prId, long afterSeq, long clientSessionId) {
        ReviewStreamBuffer.Snapshot snap = buffer.after(prId, afterSeq, clientSessionId);
        List<JsonNode> items = new ArrayList<>(snap.items().size());
        for (String json : snap.items()) {
            items.add(objectMapper.readTree(json));
        }
        meters.counter("ws.replay.items").increment(items.size());
        return new ReplayBatch(snap.sessionId(), snap.status(), snap.reason(), items);
    }
}
