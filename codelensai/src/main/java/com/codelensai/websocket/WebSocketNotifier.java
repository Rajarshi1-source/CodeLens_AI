package com.codelensai.websocket;

import com.codelensai.model.dto.ReviewStatusMessage;
import com.codelensai.model.dto.ReviewTopicMessage;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.util.StreamKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes review events to the per-PR Redis Pub/Sub channel {@code review:{prId}}. A token produced on
 * pod #1 thus reaches a client connected to pod #2 ({@code ReviewRelay} on every pod subscribes and pushes
 * to its local STOMP sessions). Every review-topic message is also appended to {@link ReviewStreamBuffer}
 * first, so a client that reconnects mid-review can replay what it missed.
 */
@Component
public class WebSocketNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebSocketNotifier.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ReviewStreamBuffer buffer;

    public WebSocketNotifier(StringRedisTemplate redis, ObjectMapper objectMapper, ReviewStreamBuffer buffer) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.buffer = buffer;
    }

    /** A new review session starts streaming for this PR. */
    public void beginSession(long prId, long sessionId) {
        buffer.reset(prId, sessionId, ReviewStatus.IN_PROGRESS.name());
        sendStatus(prId, sessionId, ReviewStatus.IN_PROGRESS, null);
    }

    public void send(long prId, ReviewTopicMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            buffer.append(prId, message.seq(), json);
            publish(prId, new ReviewBusMessage(ReviewBusMessage.REVIEW, json));
        } catch (Exception e) {
            log.warn("Failed to publish review event for prId={}: {}", prId, e.getMessage());
        }
    }

    public void sendStatus(long prId, long sessionId, ReviewStatus status, String reason) {
        try {
            buffer.setStatus(prId, status.name(), reason);
            String json = objectMapper.writeValueAsString(new ReviewStatusMessage(sessionId, status.name(), reason));
            publish(prId, new ReviewBusMessage(ReviewBusMessage.STATUS, json));
        } catch (Exception e) {
            log.warn("Failed to publish status for prId={}: {}", prId, e.getMessage());
        }
    }

    private void publish(long prId, ReviewBusMessage message) {
        redis.convertAndSend(StreamKeys.REVIEW_CHANNEL_PREFIX + prId, objectMapper.writeValueAsString(message));
    }
}
