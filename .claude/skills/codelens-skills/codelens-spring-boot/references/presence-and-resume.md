# CodeLens AI — WebSocket Auth, Resumable Stream, Presence (G5) and the Demo Profile

Load when touching `websocket/*`, `WebSocketConfig`, `AccessGuard`, or `demo/*`. Decision: ADR 0006.

## Contents
1. STOMP authentication and authorization
2. Message shapes
3. Resumable stream (replay buffer)
4. Presence
5. Demo profile

## 1. STOMP auth (`StompAuthChannelInterceptor`)

The SockJS handshake is an HTTP request carrying the session cookie, so Spring Security has already loaded the
`OAuth2AuthenticationToken`; it becomes the STOMP session's user.

```java
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    switch (accessor.getCommand()) {
        case CONNECT, STOMP -> authenticate(accessor);           // no anonymous sockets; store userId/login/avatar
        case SUBSCRIBE -> authorize(accessor, SUBSCRIBE_DEST);   // /topic/pr/{id}/(review|status|presence), /app/pr/{id}/replay
        case SEND -> authorize(accessor, SEND_DEST);             // /app/pr/{id}/presence
        default -> { }
    }
    return message;
}
```

`authorize` extracts the PR id from the destination, checks `AccessGuard.canAccessPr(prId, userId)` (cached per
STOMP session) and throws `MessageDeliveryException` otherwise (→ STOMP ERROR frame). Unknown destinations are
rejected. Handshake origins come from `codelens.web.allowed-origins` (never `*`). Do **not** add
`@EnableWebSocketSecurity`: it requires a CSRF token on CONNECT that the cookie-based SockJS client cannot send.

## 2. Message shapes

```java
// /topic/pr/{id}/review — discriminated union (Jackson @JsonTypeInfo property "type")
sealed interface ReviewTopicMessage permits ReviewStreamToken, ReviewTopicMessage.Reset, ReviewTopicMessage.ChunkError {}
record ReviewStreamToken(long sessionId, long seq, int chunk, String key, String file, int line,
                         Severity severity, String text, Double confidence, String ruleKey)  // type "token"
record Reset(long sessionId, long seq, int chunk)                                          // type "reset"
record ChunkError(long sessionId, long seq, int chunk, String file)                        // type "error"

// /topic/pr/{id}/status
record ReviewStatusMessage(long sessionId, ReviewStatus status, String reason)
```

`key` is the finding's fingerprint (`sha256(file|line|ruleKey)[:32]`), so the client groups fragments and merges
with persisted comments by key. `seq` is shared by all chunks of one session and restarts at 1 for a new session —
clients must reset their `lastSeq` when `sessionId` changes (that was the "re-review drops every token" bug).

## 3. Resumable stream

- `WebSocketNotifier.beginSession(prId, sessionId)` resets `ReviewStreamBuffer` for the PR; every event is
  published on `review:{prId}` (Pub/Sub fan-out) **and** appended to the Redis Stream `review:tokens:{prId}`
  (`MAXLEN ~5000`, 2 h TTL) together with the current session id and status.
- `@SubscribeMapping("/pr/{prId}/replay")` (headers `after-seq`, `session-id`) answers once, directly to the
  subscriber: `ReplayBatch(sessionId, status, reason, items)` — everything after `after-seq` if the session matches,
  else the whole current session. REST mirror: `GET /api/prs/{id}/stream?afterSeq=`.
- Client protocol: subscribe to review + status, **buffer** live messages, request the replay, apply the batch,
  then the buffered messages in `seq` order; duplicates are dropped by `seq`. Counter `ws.replay.items`.

## 4. Presence (G5)

- `@MessageMapping("/pr/{prId}/presence")` accepts `{type: JOIN|HEARTBEAT|FOCUS|LEAVE|TYPING, file, line, typing}`.
- `PresenceService` keeps `presence:pr:{id}` (hash STOMP-session-id → viewer JSON) and `presence:pr:{id}:seen`
  (zset session → last heartbeat ms); reads prune entries older than `codelens.presence.ttl-seconds`. No `HEXPIRE`
  (Redis 7.0 from apt lacks it).
- Every change publishes on `presence-events:{prId}`; `PresenceRelay` on each pod sends the snapshot
  `{prId, viewers[]}` to `/topic/pr/{id}/presence`. `SessionDisconnectEvent` removes the session immediately.
- Gauge `presence.viewers`. The UI collapses rows per login (two tabs = one avatar) and hides the viewer themself.

## 5. Demo profile (`demo/*`, `@Profile("demo")`)

- `DemoSafetyGuard`: fails startup if `codelens.demo.enabled` is not true, if `prod` is also active, or if a real
  GitHub client id is configured without `allow-with-real-oauth`; logs a WARN banner.
- `DemoAuthController`: `POST /api/auth/demo-login?as=alice|bob` builds a `DefaultOAuth2User` +
  `OAuth2AuthenticationToken` and saves it via `HttpSessionSecurityContextRepository` — a real session, so REST,
  STOMP and membership checks run exactly as in production. `POST /api/demo/reset` deletes the demo repository
  (cascade), its Redis keys and feedback, then re-seeds. Both are loopback-only by default.
- `DemoDataSeeder` (idempotent): users `demo-alice` (owner) and `demo-bob` (member) with negative GitHub ids, the
  repo `codelens-demo/storefront` indexed from `demo/repo/**`, five PRs from `demo/prs/NN.json` + `.diff` stored in
  `pr_diffs`; PRs 1–4 are reviewed through the real pipeline, PR 5 stays PENDING for a live re-review.
- `DemoGitHubApiClient` replaces `HttpGitHubApiClient` (`@Profile("!demo")`): file contents from the classpath,
  fake suggestion URLs, no network.
- `application-demo.yml`: local LLM (paced 700 ms), hashing embeddings, internal API on, presence TTL 20 s.
- The Playwright suite (`frontend/e2e`) runs against this profile; global setup resets it and stores Alice's and
  Bob's sessions as storage states.
