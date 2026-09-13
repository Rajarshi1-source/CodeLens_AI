package com.codelensai.websocket;

import com.codelensai.model.dto.ReviewStreamToken;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.util.StreamKeys;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes review events to the per-PR Redis Pub/Sub channel {@code review:{prId}}. A token produced
 * on pod #1 thus reaches a client connected to pod #2 (the {@code ReviewRelay} on every pod subscribes
 * and pushes to its local STOMP sessions). Token sequence numbers (assigned by {@code ReviewService})
 * make the live stream resumable on reconnect.
 */
@Component
public class WebSocketNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebSocketNotifier.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public WebSocketNotifier(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void sendToken(long prId, ReviewStreamToken token) {
        publish(prId, ReviewBusMessage.token(token));
    }

    public void sendStatus(long prId, ReviewStatus status) {
        publish(prId, ReviewBusMessage.status(status.name()));
    }

    public void sendChunkError(long prId, String fileName) {
        publish(prId, ReviewBusMessage.error(fileName));
    }

    private void publish(long prId, ReviewBusMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redis.convertAndSend(StreamKeys.REVIEW_CHANNEL_PREFIX + prId, json);
        } catch (Exception e) {
            log.warn("Failed to publish review event for prId={}: {}", prId, e.getMessage());
        }
    }
}
