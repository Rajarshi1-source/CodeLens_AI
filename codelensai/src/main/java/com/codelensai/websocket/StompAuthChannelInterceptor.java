package com.codelensai.websocket;

import com.codelensai.model.entity.User;
import com.codelensai.security.AccessGuard;
import com.codelensai.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates and authorizes STOMP frames. The WebSocket handshake carries the HTTP session cookie, so
 * the principal Spring Security loaded for the handshake becomes the STOMP session's user.
 *
 * <ul>
 *   <li><b>CONNECT</b> requires an authenticated OAuth2 principal (no anonymous sockets); the user's id,
 *       login and avatar are stored in the STOMP session attributes for presence.</li>
 *   <li><b>SUBSCRIBE / SEND</b> are allow-listed by destination and require repository membership for the
 *       PR in the path ({@link AccessGuard}); anything else is rejected. Checks are cached per session.</li>
 * </ul>
 * A rejected frame raises {@link MessageDeliveryException}, which the broker turns into an ERROR frame.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    static final String USER_ID = "codelens.userId";
    static final String LOGIN = "codelens.login";
    static final String AVATAR = "codelens.avatarUrl";
    private static final String ALLOWED_PRS = "codelens.allowedPrs";

    private static final Pattern SUBSCRIBE_DEST =
            Pattern.compile("^/(?:topic/pr/(\\d+)/(?:review|status|presence)|app/pr/(\\d+)/replay)$");
    private static final Pattern SEND_DEST = Pattern.compile("^/app/pr/(\\d+)/presence$");

    private final UserService userService;
    private final AccessGuard guard;

    public StompAuthChannelInterceptor(UserService userService, AccessGuard guard) {
        this.userService = userService;
        this.guard = guard;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> authorize(accessor, SUBSCRIBE_DEST);
            case SEND -> authorize(accessor, SEND_DEST);
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK, NACK...: nothing to check
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        OAuth2User oauth = principal instanceof AbstractAuthenticationToken token
                && token.getPrincipal() instanceof OAuth2User u ? u : null;
        if (oauth == null) {
            throw new MessageDeliveryException("unauthenticated WebSocket connection");
        }
        User user = userService.currentUser(oauth)
                .orElseThrow(() -> new MessageDeliveryException("unknown user"));
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put(USER_ID, user.getId());
            attrs.put(LOGIN, user.getUsername());
            attrs.put(AVATAR, user.getAvatarUrl());
        }
    }

    private void authorize(StompHeaderAccessor accessor, Pattern allowed) {
        String destination = accessor.getDestination();
        Matcher m = destination == null ? null : allowed.matcher(destination);
        if (m == null || !m.matches()) {
            log.debug("Rejected STOMP {} to {}", accessor.getCommand(), destination);
            throw new MessageDeliveryException("destination not allowed: " + destination);
        }
        String raw = m.group(1) != null ? m.group(1) : m.group(2);
        long prId = Long.parseLong(raw);
        Map<String, Object> attrs = accessor.getSessionAttributes();
        Object userId = attrs == null ? null : attrs.get(USER_ID);
        if (!(userId instanceof Long uid)) {
            throw new MessageDeliveryException("unauthenticated WebSocket session");
        }
        @SuppressWarnings("unchecked")
        Set<Long> allowedPrs = (Set<Long>) attrs.computeIfAbsent(ALLOWED_PRS, k -> new HashSet<Long>());
        synchronized (allowedPrs) {
            if (allowedPrs.contains(prId)) {
                return;
            }
            if (!guard.canAccessPr(prId, uid)) {
                throw new MessageDeliveryException("no access to PR " + prId);
            }
            allowedPrs.add(prId);
        }
    }
}
