# ADR 0006 — Demo profile, WebSocket (STOMP) authentication and membership checks

- Status: Accepted — amends [ADR 0004](0004-oauth2-session-vs-jwt.md) (session auth stays; JWT still deferred)
- Date: 2026-10

## Context

ADR 0004 shipped GitHub OAuth2-login sessions. Three gaps remained:

1. **No way to try the product** — or run an end-to-end test — without a GitHub OAuth app, a webhook and
   an LLM key.
2. **The WebSocket was unauthenticated**: any client could subscribe to any PR's review topic.
3. **REST endpoints checked authentication but not ownership**: user B could read user A's PRs, and
   `connect` could take over a repository someone else had connected.

A stateless JWT resource server (the master plan's sketch) solves none of these, and still has no issuer.

## Decision

- **Keep session auth** (ADR 0004). JWT remains future work for non-browser clients.
- **Membership-based access**: `repository_members` (owner/member, backfilled from `repositories.user_id`
  on every boot). `AccessGuard` checks membership for every PR / review / comment / repo endpoint and
  answers **404** on denial (no existence leak). `connect` never reassigns an owner.
- **STOMP auth** in a `ChannelInterceptor`: CONNECT requires the HTTP session's
  `OAuth2AuthenticationToken` (the SockJS handshake carries the session cookie) and stores the user in
  the session attributes; SUBSCRIBE/SEND are allow-listed to `/topic/pr/{id}/(review|status|presence)`,
  `/app/pr/{id}/replay` and `/app/pr/{id}/presence`, each checked with `AccessGuard`. Handshake origins
  come from `codelens.web.allowed-origins` instead of `*`. `@EnableWebSocketSecurity` is not used: it
  requires a CSRF token on CONNECT, which the session-cookie SockJS client has no clean way to send, and
  the interceptor gives the same guarantees explicitly.
- **`demo` Spring profile**: seeds Alice and Bob, a repository and five PRs (four reviewed through the
  real pipeline, one pending for a live re-review), uses a network-free `DemoGitHubApiClient`, the
  offline LLM (paced so streaming is visible) and hashing embeddings. `POST /api/auth/demo-login?as=alice|bob`
  builds a real `OAuth2AuthenticationToken` session, so every downstream check (REST, STOMP, membership)
  is exercised exactly as in production. `POST /api/demo/reset` re-seeds.
- **Demo safety**: the controllers exist only under `@Profile("demo")` (404 otherwise); demo login and
  reset are loopback-only by default; `DemoSafetyGuard` refuses to start when the `prod` profile is also
  active or a real GitHub client id is configured (unless explicitly overridden), and logs a banner.

## Consequences

- One command gives a reviewer a working product, and the Playwright suite runs against the real
  backend with real sessions, STOMP and Redis — no mocked auth.
- Authorization is enforced in one place for REST and WebSocket; cross-tenant reads return 404.
- The demo login is a deliberate backdoor confined by profile, loopback and the startup guard; it must
  never be enabled on an internet-facing deployment.
