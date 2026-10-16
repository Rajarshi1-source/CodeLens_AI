package com.codelensai.config;

import com.codelensai.websocket.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over SockJS. The simple broker is pod-local; cross-pod fan-out is achieved by relaying events
 * through Redis Pub/Sub (see {@code RedisConfig} + {@code WebSocketNotifier}). SockJS gives a fallback
 * transport behind corporate proxies.
 *
 * <p>Security: the handshake reuses the HTTP session, {@link StompAuthChannelInterceptor} authenticates
 * CONNECT and authorizes SUBSCRIBE/SEND per PR, and origins are an explicit allow-list (never {@code "*"},
 * which with cookie auth invites cross-site WebSocket hijacking). {@code @EnableWebSocketSecurity} is not
 * used because it demands a CSRF token on CONNECT while the app runs with CSRF disabled (see ADR-0006).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuth;
    private final WebProperties web;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuth, WebProperties web) {
        this.stompAuth = stompAuth;
        this.web = web;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(web.allowedOrigins().toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuth);
    }
}
