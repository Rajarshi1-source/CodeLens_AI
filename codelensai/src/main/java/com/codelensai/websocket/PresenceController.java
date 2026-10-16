package com.codelensai.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * Inbound presence updates on {@code /app/pr/{id}/presence} (JOIN, HEARTBEAT, FOCUS, TYPING, LEAVE).
 * The viewer's identity comes from the STOMP session attributes set at CONNECT by
 * {@link StompAuthChannelInterceptor} — never from the payload — and membership was checked on SEND.
 */
@Controller
public class PresenceController {

    public record PresenceUpdate(String type, String file, Integer line, Boolean typing) {
    }

    private final PresenceService presence;

    public PresenceController(PresenceService presence) {
        this.presence = presence;
    }

    @MessageMapping("/pr/{prId}/presence")
    public void update(@DestinationVariable long prId, @Payload PresenceUpdate update, SimpMessageHeaderAccessor headers) {
        String sessionId = headers.getSessionId();
        Map<String, Object> attrs = headers.getSessionAttributes();
        if (sessionId == null || attrs == null || !(attrs.get(StompAuthChannelInterceptor.USER_ID) instanceof Long userId)) {
            return;
        }
        String type = update == null || update.type() == null ? "HEARTBEAT" : update.type().toUpperCase();
        if ("LEAVE".equals(type)) {
            presence.leave(prId, sessionId);
            return;
        }
        presence.update(prId, new PresenceService.Viewer(
                sessionId,
                userId,
                String.valueOf(attrs.get(StompAuthChannelInterceptor.LOGIN)),
                attrs.get(StompAuthChannelInterceptor.AVATAR) == null ? null : String.valueOf(attrs.get(StompAuthChannelInterceptor.AVATAR)),
                update == null ? null : update.file(),
                update == null ? null : update.line(),
                update != null && Boolean.TRUE.equals(update.typing()),
                System.currentTimeMillis()));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presence.disconnect(event.getSessionId());
    }
}
