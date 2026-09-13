package com.codelensai.websocket;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes (via {@code RedisConfig}'s listener container) to {@code review:*} and pushes each event
 * to the STOMP sessions connected to THIS pod. Combined with {@link WebSocketNotifier}'s publish, this
 * is the cross-pod fan-out: produce anywhere, deliver everywhere.
 */
@Component
public class ReviewRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewRelay.class);

    private final SimpMessagingTemplate stomp;
    private final ObjectMapper objectMapper;

    public ReviewRelay(SimpMessagingTemplate stomp, ObjectMapper objectMapper) {
        this.stomp = stomp;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String prId = channel.substring(channel.indexOf(':') + 1);
            ReviewBusMessage bus = objectMapper.readValue(message.getBody(), ReviewBusMessage.class);

            switch (bus.kind()) {
                case ReviewBusMessage.TOKEN ->
                        stomp.convertAndSend("/topic/pr/" + prId + "/review", bus.token());
                case ReviewBusMessage.STATUS ->
                        stomp.convertAndSend("/topic/pr/" + prId + "/status", bus.status());
                case ReviewBusMessage.ERROR ->
                        stomp.convertAndSend("/topic/pr/" + prId + "/review",
                                "{\"error\":\"chunk failed\",\"file\":\"" + bus.errorFile() + "\"}");
                default -> log.debug("Unknown review bus kind: {}", bus.kind());
            }
        } catch (Exception e) {
            log.warn("Failed to relay review message: {}", e.getMessage());
        }
    }
}
