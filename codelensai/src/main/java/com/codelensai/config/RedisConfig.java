package com.codelensai.config;

import com.codelensai.websocket.ReviewRelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the Redis Pub/Sub listener container that powers cross-pod WebSocket fan-out: every pod
 * subscribes to {@code review:*} and relays events to its local STOMP sessions via {@link ReviewRelay}.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer reviewListenerContainer(RedisConnectionFactory connectionFactory,
                                                                 ReviewRelay reviewRelay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(reviewRelay, new PatternTopic("review:*"));
        return container;
    }
}
