package com.codelensai.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Fans presence snapshots from Redis ({@code presence-events:*}) out to this pod's STOMP subscribers. */
@Component
public class PresenceRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PresenceRelay.class);

    private final SimpMessagingTemplate stomp;

    public PresenceRelay(SimpMessagingTemplate stomp) {
        this.stomp = stomp;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String prId = channel.substring(channel.indexOf(':') + 1);
            stomp.convertAndSend("/topic/pr/" + prId + "/presence", new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to relay presence: {}", e.getMessage());
        }
    }
}
