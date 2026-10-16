package com.codelensai.websocket;

import com.codelensai.config.PresenceProperties;
import com.codelensai.util.StreamKeys;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * G5 collaborative presence: who is looking at a PR right now, which file/line they are focused on, and
 * whether they are typing. State lives in Redis so every pod sees every viewer:
 * <ul>
 *   <li>{@code presence:pr:{id}} — hash STOMP-session-id → viewer JSON;</li>
 *   <li>{@code presence:pr:{id}:seen} — zset STOMP-session-id → last heartbeat (ms).</li>
 * </ul>
 * Stale viewers (silent for {@code ttl-seconds}) are pruned on every update — a per-field hash TTL
 * ({@code HEXPIRE}) would need Redis 7.4+. Each change publishes a full snapshot on
 * {@code presence-events:{prId}}, which {@link PresenceRelay} fans out to {@code /topic/pr/{id}/presence}.
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final Duration KEY_TTL = Duration.ofHours(1);

    public record Viewer(String sessionId, long userId, String login, String avatarUrl, String file, Integer line,
                         boolean typing, long lastSeen) {
    }

    public record Snapshot(long prId, List<Viewer> viewers) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PresenceProperties props;
    /** PRs each local STOMP session joined (for disconnect cleanup). */
    private final Map<String, Set<Long>> sessionPrs = new ConcurrentHashMap<>();

    public PresenceService(StringRedisTemplate redis, ObjectMapper objectMapper, PresenceProperties props,
                           MeterRegistry meters) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
        Gauge.builder("presence.viewers", sessionPrs, m -> m.values().stream().mapToInt(Set::size).sum())
                .description("PR presence memberships held by STOMP sessions on this pod")
                .register(meters);
    }

    public void update(long prId, Viewer viewer) {
        String key = key(prId);
        redis.opsForHash().put(key, viewer.sessionId(), objectMapper.writeValueAsString(viewer));
        redis.opsForZSet().add(seenKey(prId), viewer.sessionId(), viewer.lastSeen());
        redis.expire(key, KEY_TTL);
        redis.expire(seenKey(prId), KEY_TTL);
        sessionPrs.computeIfAbsent(viewer.sessionId(), k -> ConcurrentHashMap.newKeySet()).add(prId);
        publish(prId);
    }

    public void leave(long prId, String sessionId) {
        redis.opsForHash().delete(key(prId), sessionId);
        redis.opsForZSet().remove(seenKey(prId), sessionId);
        Set<Long> prs = sessionPrs.get(sessionId);
        if (prs != null) {
            prs.remove(prId);
        }
        publish(prId);
    }

    /** STOMP session closed: remove it from every PR it was viewing. */
    public void disconnect(String sessionId) {
        Set<Long> prs = sessionPrs.remove(sessionId);
        if (prs == null) {
            return;
        }
        for (long prId : prs) {
            leave(prId, sessionId);
        }
    }

    public Snapshot snapshot(long prId) {
        prune(prId);
        List<Viewer> viewers = new ArrayList<>();
        for (Object json : redis.opsForHash().values(key(prId))) {
            try {
                viewers.add(objectMapper.readValue(String.valueOf(json), Viewer.class));
            } catch (Exception e) {
                log.debug("Skipping malformed presence entry: {}", e.getMessage());
            }
        }
        viewers.sort(Comparator.comparing(Viewer::login).thenComparing(Viewer::sessionId));
        return new Snapshot(prId, viewers);
    }

    private void prune(long prId) {
        long cutoff = System.currentTimeMillis() - props.ttlSeconds() * 1000L;
        Set<String> stale = redis.opsForZSet().rangeByScore(seenKey(prId), 0, cutoff);
        if (stale != null && !stale.isEmpty()) {
            redis.opsForHash().delete(key(prId), stale.toArray());
            redis.opsForZSet().remove(seenKey(prId), stale.toArray());
        }
    }

    private void publish(long prId) {
        try {
            redis.convertAndSend(StreamKeys.PRESENCE_CHANNEL_PREFIX + prId, objectMapper.writeValueAsString(snapshot(prId)));
        } catch (Exception e) {
            log.warn("Failed to publish presence for prId={}: {}", prId, e.getMessage());
        }
    }

    private static String key(long prId) {
        return "presence:pr:" + prId;
    }

    private static String seenKey(long prId) {
        return "presence:pr:" + prId + ":seen";
    }
}
