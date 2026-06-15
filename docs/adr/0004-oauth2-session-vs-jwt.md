# ADR 0004 — OAuth2-login session auth (JWT resource server deferred)

- Status: Accepted (deviation from the master plan, to revisit)
- Date: 2026

## Context

The master plan sketches a stateless API secured with `oauth2ResourceServer().jwt()` alongside GitHub
OAuth2 login. A JWT resource server needs an issuer/decoder for tokens we do not mint yet; wiring it
with no issuer fails Spring context startup.

## Decision

Ship **GitHub OAuth2-login with server-side sessions** for the MVP, and **omit** the JWT
resource-server wiring for now. Specifics:

- `SecurityConfig` permits the webhook (HMAC-verified, not session-auth), `/ws/**`, `/api/internal/**`,
  `/api/auth/**`, and actuator health; everything else under `/api/**` requires an authenticated session.
- An `AuthenticationEntryPoint` returns `401` (not a `302` redirect) for unauthenticated `/api/**`
  XHR calls, so the SPA can react to expired sessions.
- GitHub access tokens are encrypted at rest (AES-GCM JPA converter); never exposed via DTOs.
- Behind the frontend nginx proxy, `server.forward-headers-strategy=framework` lets Spring build the
  correct same-origin OAuth2 redirect URI from `X-Forwarded-*` headers.

## Consequences

- Simple, correct auth for a single-origin SPA + backend deployment.
- A future move to stateless JWT (for multiple clients / API tokens) is isolated to `SecurityConfig`
  plus a token issuer; the rest of the app is unaffected.
