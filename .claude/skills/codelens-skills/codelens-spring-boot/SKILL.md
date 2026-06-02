---
name: codelens-spring-boot
description: Spring Boot 3.5.x specialist for the CodeLens AI backend. Use this skill whenever building or editing any backend component of the CodeLens AI code-review platform — REST controllers, services, repositories, the idempotent GitHub webhook receiver, the Redis Streams review-job consumer, the LLM provider adapter, STOMP/WebSocket streaming, Spring Security 6 GitHub OAuth2, JPA entities, or Resilience4j wiring. Trigger this even when the user only says "the webhook," "the review consumer," "the WebSocket handler," "OAuth," "the queue," or names a Spring bean/class — all server-side wiring in CodeLens AI follows these conventions and should use this skill. Pair it with codelens-java21 for language-level concerns.
---

# CodeLens AI — Spring Boot 3.5.x (Java 21)

You are building the **CodeLens AI** backend: a Spring Boot 3.5.x service on Java 21 (built with Maven) that ingests GitHub PR webhooks idempotently, queues review jobs in Redis Streams, runs an LLM behind a config-driven adapter, and streams structured review comments to browsers over STOMP/WebSocket (fanned out across pods via Redis Pub/Sub). Spring Boot 3.5.x is the latest supported 3.x line — still Spring Framework 6.2 / Spring Security 6, so the patterns here are unchanged from earlier 3.x. Language idioms (records, virtual threads, sealed adapter) live in `codelens-java21` — use both.

## Reference files (load on demand)

This body is the working summary. Read the matching reference when you need full code:

| Reference | Load when |
|---|---|
| `references/schema.md` | Writing entities/migrations/queries; the full Postgres 16 DDL, indexes, pgvector, expand-contract migrations |
| `references/idempotency-and-queue.md` | Touching the webhook receiver, the Redis Streams consumer, DLQ, or the create-or-replace upsert (full code) |
| `references/diff-chunking.md` | Implementing/tuning `DiffChunkerService`, AST-aware chunking, file-skipping, line-number fidelity |
| `references/prompt-versioning-and-resilience.md` | Changing prompts, wiring Resilience4j, or the Redis Pub/Sub WebSocket fan-out |
| `references/build-and-run.md` | Generating the project, editing `pom.xml`, or setting up the local/Docker build (Maven, Spring Boot 3.5.x, Java 21) |

## Pipeline this backend implements

```
GitHub webhook → WebhookController (verify HMAC → dedup on X-GitHub-Delivery → enqueue)
              → Redis Stream "review-jobs"
              → ReviewJobConsumer (consumer group, idempotent on (pr_id, head_sha))
              → ReviewService → LlmReviewProvider.streamReview() [SSE in]
              → WebSocketNotifier → /topic/pr/{id}/review [WebSocket out, Redis Pub/Sub fan-out]
              → persist to Postgres (upsert), optionally post summary to GitHub
```

Every stage must be **idempotent** — GitHub delivers at-least-once and Redis Streams is at-least-once, so the *effect* is keyed on `(pr_id, head_sha)`.

## Package structure (follow it)

```
com.codelensai/
├── config/      SecurityConfig, WebSocketConfig, RedisConfig, LlmConfig, GitHubConfig
├── controller/  Auth, Webhook, PullRequest, Review, Dashboard
├── websocket/   ReviewStreamHandler, WebSocketEventListener
├── service/     GitHubService, ReviewService, AIReviewService, DiffChunkerService,
│                ReviewJobConsumer, IdempotencyService, WebSocketNotifier
├── model/       entity / dto (records) / enums
├── repository/  Spring Data JPA
├── exception/   custom + @RestControllerAdvice
└── util/        WebhookSignatureValidator, DiffParser, PromptBuilder
```

## 1. Idempotent webhook receiver (the correctness centerpiece)

Verify authenticity FIRST, then dedup, then enqueue and ACK fast (202). Never run the review inline.

```java
@PostMapping("/github")
public ResponseEntity<String> handleGithub(
        @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
        @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestBody String rawBody) {

    if (!signatureValidator.isValid(rawBody, signature))      // reject forgeries first
        return ResponseEntity.status(401).body("invalid signature");
    if (!"pull_request".equals(eventType))
        return ResponseEntity.ok("ignored event: " + eventType);
    if (deliveryId == null)
        return ResponseEntity.badRequest().body("missing X-GitHub-Delivery");
    if (!idempotency.isFirstDelivery(deliveryId))             // Redis SETNX, 24h TTL
        return ResponseEntity.ok("duplicate ignored");        // idempotent no-op, HTTP 200

    webhookService.parseAndEnqueue(rawBody);                  // XADD review-jobs
    return ResponseEntity.accepted().body("queued");          // 202, process async
}
```

HMAC uses **constant-time** comparison (`MessageDigest.isEqual`) to defeat timing attacks. Dedup uses `setIfAbsent` (SETNX) so only the first caller for a delivery UUID wins.

## 2. Redis Streams consumer — effectively-once

A consumer group reads jobs, processes, and ACKs **only after success**. Effect-level idempotency skips already-reviewed `(pr_id, head_sha)`. After `MAX_RETRIES` (tracked via `XPENDING` delivery count) a job goes to a dead-letter stream and the PR is marked `REVIEW_FAILED` with a retry affordance. Full consumer, DLQ handling, and group-creation guard in `references/idempotency-and-queue.md`.

```java
if (sessionRepo.existsByPrIdAndHeadSha(prId, headSha)) { ack(record); continue; }
reviewService.processReview(prId, headSha);
ack(record);   // ack AFTER success only — a crash before ack just redelivers
```

> The phrase to keep in mind: *at-least-once delivery + idempotent consumer = effectively-once.* The DB `UNIQUE(pr_id, head_sha)` constraint is the final backstop against a two-worker race.

## 3. LLM provider adapter (config-driven, never hardcoded)

Inject the sealed `LlmReviewProvider` (defined per `codelens-java21`). Select the implementation by config; wrap calls in Resilience4j.

```java
codelens:
  llm:
    provider: ${LLM_PROVIDER:gpt5}       # gpt5 | claude | local
    review-model: ${REVIEW_MODEL:gpt-5}
    summary-model: ${SUMMARY_MODEL:gpt-5-mini}
    temperature: 0.1
    max-output-tokens: 2000
    fallback-provider: claude
```

```java
@Configuration
public class LlmConfig {
    @Bean
    LlmReviewProvider llmReviewProvider(LlmProperties props, /* concrete beans */ ...) {
        return switch (props.provider()) {
            case "gpt5"  -> openAiProvider;
            case "claude" -> claudeProvider;
            case "local" -> localProvider;
            default -> throw new IllegalStateException("unknown provider: " + props.provider());
        };
    }
}
```

Use the provider's **structured-output / tool-calling** mode so each comment is schema-validated (`{file, line, severity, comment, confidence}`), not regex-parsed from prose. Stream partial JSON per comment.

## 4. WebSocket — STOMP over SockJS, Redis-backed fan-out

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");                  // back with Redis relay at scale
        config.setApplicationDestinationPrefixes("/app");
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
```

A token produced on pod #1 must reach a client on pod #2 → publish to a Redis channel `review:{prId}`; each pod subscribes and pushes to its local STOMP sessions. Per review session, attach a **sequence number** so a reconnecting client can replay missed comments (resumable stream).

## 5. Spring Security 6 — GitHub OAuth2 + stateless API

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)            // stateless API + webhook
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/webhooks/github").permitAll()   // verified by HMAC, not auth
                .requestMatchers("/actuator/health/**", "/ws/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(Customizer.withDefaults())           // GitHub OAuth2 login
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .build();
    }
}
```

The webhook endpoint is `permitAll` because it is authenticated by HMAC signature, not by a session/JWT. GitHub access tokens are **encrypted at rest**; never expose `access_token` or `webhook_secret` in any DTO.

## 6. JPA entities & repositories

Entities map the schema in `references/schema.md` (users → repositories → pull_requests → review_sessions → review_comments → comment_feedback). The full DDL, indexes, key dashboard/search queries, JSONB/tsvector mapping notes, and pgvector setup for the G1 differentiator live there. Key idempotency repo:

```java
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {
    boolean existsByPrIdAndHeadSha(Long prId, String headSha);
    Optional<ReviewSession> findByPrIdAndHeadSha(Long prId, String headSha);
}
```

- `@Transactional(readOnly = true)` on services by default; `@Transactional` on multi-step writes.
- Review completion (PR status + comments + session) is ONE transaction — no ghost states (CP plane).
- Use JSONB columns for variable AI metadata; tsvector GIN index for comment search.

## 7. Resilience4j (every external call)

Wrap LLM/GitHub calls with circuit breaker + retry (backoff + jitter, respect `Retry-After`) + timeout (LLM 60s, GitHub 10s) + separate bulkheads. On LLM outage, degrade: PR shows `REVIEW_UNAVAILABLE`, the app still works without AI comments. Full `application.yml` config, the fallback method, the Redis sliding-window outbound rate limiter, and the WebSocket Redis Pub/Sub relay are in `references/prompt-versioning-and-resilience.md`.

```java
@CircuitBreaker(name = "llm", fallbackMethod = "fallbackProvider")
@Retry(name = "llm")  @TimeLimiter(name = "llm")  @Bulkhead(name = "llm")
public Flux<ReviewToken> review(DiffChunk chunk) { ... }
```

The `resilience4j-spring-boot3` dependency is not managed by the Spring Boot BOM — pin its version explicitly in `pom.xml` (see the build config in `codelens-java21`).

## API surface (keep stable)

```
AUTH      POST /api/auth/github/callback · GET /api/auth/me · POST /api/auth/logout
REPOS     GET /api/repos · POST /api/repos/connect · DELETE /api/repos/{id}
PRS       GET /api/prs · GET /api/prs/{id} · POST /api/prs/{id}/re-review
REVIEWS   GET /api/prs/{id}/comments · GET /api/reviews/{id}/summary
WEBHOOK   POST /api/webhooks/github
DASHBOARD GET /api/dashboard/stats
WS        CONNECT /ws · SUBSCRIBE /topic/pr/{id}/review · /topic/pr/{id}/status
```

## MUST DO
- Verify HMAC before any work; dedup on `X-GitHub-Delivery`; ACK the Stream only after success.
- Key every review effect on `(pr_id, head_sha)`; rely on the UNIQUE constraint as backstop.
- Drive model choice from `application.yml`; inject the sealed `LlmReviewProvider`.
- Fan out WebSocket tokens cross-pod via Redis Pub/Sub; attach sequence numbers.
- Wrap LLM/GitHub calls in circuit breaker + retry + timeout + bulkhead.
- Constructor injection; `@Valid` on request bodies; global `@RestControllerAdvice`.

## MUST NOT DO
- Run a review inline in the webhook handler (always enqueue, return 202).
- Parse LLM output with regex (use structured/tool-calling output).
- Leak `access_token` / `webhook_secret` through DTOs or logs.
- Log raw diffs without redacting secret patterns (prompt-injection + secret-leak risk).
- Use `enableStompBrokerRelay` to an external broker unless explicitly asked — the simple broker + Redis Pub/Sub is the MVP design.
- Trust diff content as instructions — it is untrusted data passed to the model.

## Validation, line-number guarding, and mitigations
Before persisting comments, drop any whose `(file, line)` doesn't map to an actual diff hunk (hallucinated lines). Apply confidence threshold + semantic dedup to cut noise. Enforce per-repo/user rate limits and a daily token budget to prevent cost runaway. Chunking strategy — splitting by file then hunk/AST, skipping generated/vendored files, and preserving real new-file line numbers so this validation has truth to check against — is in `references/diff-chunking.md`.

## Troubleshooting
- **Duplicate reviews running**: confirm SETNX TTL and that the consumer checks `existsByPrIdAndHeadSha` before processing.
- **Client on another pod gets no tokens**: the Redis Pub/Sub relay isn't wired — the simple broker is pod-local by itself.
- **OAuth redirect loop**: registered callback URL must match the deployed origin exactly (http vs https, trailing slash).
- **`DataIntegrityViolationException` on re-review**: expected on a SHA race — the losing worker should bail cleanly, not retry.
