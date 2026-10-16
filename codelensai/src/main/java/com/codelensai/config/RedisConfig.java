package com.codelensai.config;

import com.codelensai.util.StreamKeys;
import com.codelensai.websocket.PresenceRelay;
import com.codelensai.websocket.ReviewRelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the Redis Pub/Sub listener container that powers cross-pod WebSocket fan-out: every pod subscribes
 * to {@code review:*} (review tokens/status, via {@link ReviewRelay}) and {@code presence-events:*}
 * (collaborative presence, via {@link PresenceRelay}) and relays events to its local STOMP sessions.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer reviewListenerContainer(RedisConnectionFactory connectionFactory,
                                                                 ReviewRelay reviewRelay,
                                                                 PresenceRelay presenceRelay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(reviewRelay, new PatternTopic(StreamKeys.REVIEW_CHANNEL_PREFIX + "*"));
        container.addMessageListener(presenceRelay, new PatternTopic(StreamKeys.PRESENCE_CHANNEL_PREFIX + "*"));
        return container;
    }
}
