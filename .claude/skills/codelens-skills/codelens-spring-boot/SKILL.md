---
name: codelens-spring-boot
description: Spring Boot 4.1.x specialist for the CodeLens AI backend. Use this skill whenever building or editing any backend component of the CodeLens AI code-review platform — REST controllers, services, repositories, the idempotent GitHub webhook receiver, the Redis Streams review/index consumers, the LLM provider adapter (Claude/GPT-5/local, fallback, token budget, cost), G1 retrieval (pgvector index, embeddings), G3 risk scoring, the feedback flywheel and apply-suggestion, STOMP/WebSocket streaming (auth, replay, presence), Spring Security 7 GitHub OAuth2 sessions and membership checks, the demo profile, JPA entities, or Resilience4j wiring. Trigger this even when the user only says "the webhook," "the review consumer," "the WebSocket handler," "OAuth," "the queue," "retrieval," "the risk score," "the demo," or names a Spring bean/class — all server-side wiring in CodeLens AI follows these conventions and should use this skill. Pair it with codelens-java21 for language-level concerns.
---

# CodeLens AI — Spring Boot 4.1.x (Java 21)

You are building the **CodeLens AI** backend: a Spring Boot 4.1.x service on Java 21 (built with Maven) that ingests GitHub PR webhooks idempotently, queues review jobs in Redis Streams, retrieves repository context from a pgvector index, runs an LLM (Claude, GPT-5 or an offline mock) behind a sealed adapter with retries, a cross-vendor fallback and a daily token budget, scores the PR's risk, and streams findings to browsers over authenticated STOMP (fanned out across pods via Redis Pub/Sub, resumable from a Redis replay buffer). Spring Boot 4.1.x runs on Spring Framework 7.0 / Spring Security 7.1; Java 21 remains the target. Language idioms (records, sealed types, virtual threads, the `ReviewEvent` stream) live in `codelens-java21` — use both.

## Reference files (load on demand)

| Reference | Load when |
|---|---|
| `references/schema.md` | Entities, `schema.sql` (idempotent DDL), indexes, pgvector `code_chunks`/`code_symbols`, membership, feedback, `pr_diffs` |
| `references/idempotency-and-queue.md` | The webhook receiver, the Redis Streams consumers (`review-jobs`, `index-jobs`), DLQ, forced re-review, the create-or-replace upsert |
| `references/diff-chunking.md` | `DiffChunkerService`, structure-aware (G4) chunking with `CodeStructureParser`, file skipping, line-number fidelity |
| `references/prompt-versioning-and-resilience.md` | Prompts (`review_v2`), Resilience4j around the provider stream, the fallback provider, the Redis Pub/Sub relay |
| `references/llm-providers.md` | `ReviewEvent`, the Claude SDK provider, OpenAI incremental streaming, `StreamingCommentParser`, token budget, pricing, refusals |
| `references/retrieval-and-risk.md` | G1 indexing/retrieval/embeddings, offline cross-file rules, G3 risk scoring, feedback + suppression, apply suggestion |
| `references/presence-and-resume.md` | STOMP auth interceptor, membership checks, replay buffer + `@SubscribeMapping`, presence, the demo profile |
| `references/build-and-run.md` | `pom.xml`, local/Docker/WSL build, demo, tests, E2E, eval, environment variables (identical to the repo's `build-and-run.md`) |

## Pipeline this backend implements

```
GitHub webhook → WebhookController (per-repo HMAC, then global → dedup X-GitHub-Delivery → enqueue)
              → Redis Stream "review-jobs" → ReviewJobConsumer (idempotent on (pr_id, head_sha); force for re-review)
              → ReviewService: DiffSnapshotService (pr_diffs) → DiffChunkerService (structure-aware)
                → ContextRetrievalService (callers → tests → pgvector neighbours) per chunk
                → AIReviewService.stream(ctx): budget reserve → provider Flux<ReviewEvent> → reconcile
                → WebSocketNotifier → /topic/pr/{id}/review (+ Redis replay buffer, Pub/Sub fan-out)
              → validate/filter (line guard, confidence, G7 suppression) → RiskScoringService (G3)
              → ReviewPersistenceService.completeReview (one tx: comments, usage, cost, risk, feedback relink)
              → GitHub summary comment (risk, reasons, cost)
Merged PR / connect / reindex → Redis Stream "index-jobs" → IndexJobConsumer → RepoIndexService (pgvector)
```

Every stage is **idempotent** — GitHub delivers at-least-once and Redis Streams is at-least-once, so the *effect* is keyed on `(pr_id, head_sha)`.

## Package structure (follow it)

```
com.codelensai/
├── config/        Security, WebSocket, Redis, Llm, Embedding, WebMvc + one @ConfigurationProperties record per area
├── controller/    Auth, Webhook, PullRequest, Review, Comment, Repository, Dashboard, InternalReview
├── security/      AccessGuard, CurrentUser(+resolver), InternalApiGuardFilter, CryptoStringConverter
├── websocket/     StompAuthChannelInterceptor, WebSocketNotifier, ReviewRelay, ReviewStreamBuffer,
│                  ReviewReplayController/Service, PresenceController/Service/Relay
├── demo/          DemoAuthController, DemoDataSeeder, DemoGitHubApiClient, DemoSafetyGuard (@Profile("demo"))
├── observability/ LangfuseTracer, BudgetMetrics
├── service/       ReviewService, AIReviewService, ReviewPersistenceService, DiffChunkerService, DiffSnapshotService,
│   │              ReviewJobConsumer, ReviewQueueService, WebhookService, RepositoryService, DashboardService, ...
│   ├── llm/       LlmReviewProvider (sealed) + Local/OpenAiGpt5/AnthropicClaude, StreamingCommentParser, registry
│   ├── code/      CodeStructureParser (JavaParser, Brace, Indent), SymbolExtractor
│   ├── embedding/ EmbeddingProvider (sealed): Hashing, OpenAi
│   ├── index/     RepoIndexService, CodeChunker, IndexQueueService, IndexJobConsumer
│   ├── retrieval/ ContextRetrievalService, PgVectorContextSource, InMemoryContextSource
│   ├── risk/      RiskScoringService, ReviewSummaryService, ReviewCommentFormatter
│   ├── feedback/  FeedbackService, SuppressionService, FeedbackExportService
│   ├── suggestion/ SuggestionApplyService
│   ├── cost/      TokenBudgetService, ModelPricing
│   └── github/    GitHubApiClient (interface), HttpGitHubApiClient (@Profile("!demo"))
├── model/         entity / dto (records) / enums
├── repository/    Spring Data JPA + CodeIndexRepository (JdbcClient, pgvector)
└── util/          WebhookSignatureValidator, DiffParser, PromptBuilder
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

    if (!isAuthentic(rawBody, signature))                     // the repo's own secret, then the global one
        return ResponseEntity.status(401).body("invalid signature");
    if (!"pull_request".equals(eventType))
        return ResponseEntity.ok("ignored event: " + eventType);
    if (deliveryId == null)
        return ResponseEntity.badRequest().body("missing X-GitHub-Delivery");
    if (!idempotency.isFirstDelivery(deliveryId))             // Redis SETNX, 24h TTL
        return ResponseEntity.ok("duplicate ignored");

    webhookService.parseAndEnqueue(rawBody);                  // XADD review-jobs (merged PR → XADD index-jobs)
    return ResponseEntity.accepted().body("queued");
}
```

HMAC uses **constant-time** comparison (`MessageDigest.isEqual`). Repositories connected through the UI get a per-repo webhook secret — verify against it first, otherwise automatically created webhooks always fail.

## 2. Redis Streams consumers — effectively-once

A consumer group reads jobs, processes, and ACKs **only after success**. Effect-level idempotency skips an already-reviewed `(pr_id, head_sha)` — except when the job carries `force=true` (the Re-review button), which replaces the session. After `MAX_RETRIES` (via `XPENDING` delivery count) a job goes to a dead-letter stream and the PR is `REVIEW_FAILED` (`RETRIES_EXHAUSTED`). `IndexJobConsumer` mirrors this for `index-jobs`.

> *at-least-once delivery + idempotent consumer = effectively-once.* `UNIQUE(pr_id, head_sha)` is the final backstop.

## 3. LLM providers, fallback, budget, cost

Inject the sealed `LlmReviewProvider` (see `codelens-java21`): `Flux<ReviewEvent> streamReview(ReviewPromptContext)`, `Mono<SummaryResult> summarize(SummaryContext)`, `providerId()`, `reviewModel()`. `LlmProviderRegistry` resolves primary and fallback by id with an exhaustive switch.

```yaml
codelens:
  llm:
    provider: ${LLM_PROVIDER:local}                 # local | gpt5 | claude
    fallback-provider: ${LLM_FALLBACK_PROVIDER:local}
    claude:
      api-key: ${ANTHROPIC_API_KEY:}
      review-model: ${CLAUDE_REVIEW_MODEL:claude-opus-5-5}
      summary-model: ${CLAUDE_SUMMARY_MODEL:claude-opus-5-5}   # claude-haiku-5-5 = cheaper
      effort: ${CLAUDE_EFFORT:high}
      max-tokens: ${CLAUDE_MAX_TOKENS:16000}
    budget:
      daily-tokens: ${LLM_DAILY_TOKEN_BUDGET:2000000}
    pricing:
      models:                                       # USD per Mtok; bracket keys keep the dots/dashes
        "[claude-opus-5-5]": { input-per-mtok: 4.00, output-per-mtok: 20.00 }
```

- **Claude** uses the official Anthropic Java SDK: structured JSON-schema output, `effort` set explicitly, **no temperature and no thinking config** on `claude-opus-5-5`, `maxRetries(0)` (Resilience4j owns retries), server-side refusal fallback except on Haiku. Details in `references/llm-providers.md`.
- **Structured output, parsed incrementally**: `StreamingCommentParser` emits each `comments[]` object as soon as it closes — never regex-parse prose.
- **`AIReviewService.stream(ctx)`** wraps the provider in `@Retry(fallbackMethod = "fallback")` + `@CircuitBreaker` + `@Bulkhead`, prefixes `Reset("subscribe")` so a retried Flux de-duplicates, reserves tokens before the call and reconciles with the real `Usage`. `BudgetExceededException` / `LlmRefusalException` are in `ignoreExceptions`.
- **Budget**: `TokenBudgetService` (Lua reserve/reconcile/release on a UTC-day key, skipped for zero-price models). All chunks over budget → `REVIEW_UNAVAILABLE` with `failure_reason=BUDGET_EXCEEDED`.
- **Cost**: `ModelPricing` → `cost_usd`, `input_tokens`, `output_tokens`, `model_used = provider:model` on the session.

## 4. WebSocket — authenticated STOMP, Redis fan-out, replay, presence

```java
@Override public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOriginPatterns(web.allowedOrigins().toArray(String[]::new)).withSockJS();
}
@Override public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(stompAuthChannelInterceptor);   // CONNECT needs the session's OAuth2 user;
}                                                             // SUBSCRIBE/SEND allow-listed + AccessGuard
```

- Destinations: `/topic/pr/{id}/review` (discriminated `{type: token|reset|error}` messages with `sessionId` + `seq`), `/topic/pr/{id}/status` (`ReviewStatusMessage(sessionId, status, reason)`), `/topic/pr/{id}/presence`, `@SubscribeMapping("/pr/{id}/replay")` (headers `after-seq`, `session-id`), `@MessageMapping("/pr/{id}/presence")`.
- Cross-pod: publish `ReviewBusMessage(topic, payload)` on `review:{prId}`; `ReviewRelay` on every pod forwards to local sessions. Every review event is also appended to the Redis Stream `review:tokens:{prId}` (reset per session) for replay.
- Never use `@EnableWebSocketSecurity` here (it demands a CSRF token on CONNECT); the interceptor is the auth layer (ADR 0006).

## 5. Spring Security 7 — GitHub OAuth2 sessions + membership

```java
return http
    .csrf(AbstractHttpConfigurer::disable)
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/webhooks/**").permitAll()     // verified by HMAC, not auth
        .requestMatchers("/ws/**", "/actuator/**", "/api/auth/**").permitAll()
        .requestMatchers("/api/internal/**").permitAll()     // InternalApiGuardFilter: 404 unless enabled, X-Internal-Token
        .requestMatchers("/api/demo/**").permitAll()         // controller exists only under @Profile("demo")
        .anyRequest().authenticated())
    .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))   // 401 for XHR, not a 302
    .oauth2Login(o -> o.successHandler(oauth2SuccessHandler))
    .logout(Customizer.withDefaults())
    .build();
```

- **Sessions, not JWT** (ADR 0004/0006). Stateless JWT is deferred until a non-browser client exists.
- **Authorization is membership-based**: controllers take `@CurrentUser User user` and call `AccessGuard` (`requirePr`, `requireSession`, `requireComment`; `isMember` for repositories), which answers **404** on denial. `RepositoryService.connect` never reassigns an existing owner.
- GitHub access tokens are encrypted at rest (AES-GCM converter); never expose `access_token` or `webhook_secret` in a DTO.
- **Demo profile**: `POST /api/auth/demo-login?as=alice|bob` builds a real `OAuth2AuthenticationToken` session (loopback-only); `DemoSafetyGuard` refuses to start with `prod` or a real GitHub client id.

## 6. Persistence

`schema.sql` is the schema (no Flyway): idempotent DDL run on every start — `CREATE EXTENSION IF NOT EXISTS vector`, `ADD COLUMN IF NOT EXISTS`, no `DO $$` blocks (Spring's `ScriptUtils` splits on `;`). Entities map users → repositories (+ `repository_members`) → pull_requests → review_sessions → review_comments → comment_feedback, plus `pr_diffs`; `code_chunks`/`code_symbols` are `JdbcClient`-only. Full DDL in `references/schema.md`.

- `@Transactional(readOnly = true)` by default; review completion (session + comments + PR status/risk + feedback relink) is ONE transaction.
- JSONB for variable metadata (`servedBy`, `fallbackChunks`, `suppressed`, retrieval stats, `apply` state).

## 7. Resilience4j (every external call)

Circuit breaker + retry (backoff + jitter) + timeout + separate bulkheads for LLM, GitHub and embeddings. The GitHub annotations live on `HttpGitHubApiClient` (a separate bean) — annotations on methods called from inside the same class are bypassed by the proxy.

Use `resilience4j-spring-boot4` (pinned; not in the Boot BOM) **and `spring-boot-starter-aspectj`** — without the AspectJ starter every `@CircuitBreaker`/`@Retry`/`@Bulkhead` is a silent no-op on Boot 4.

## API surface (keep stable; `scripts/check_skill_consistency.py` checks it against the controllers)

```
AUTH      GET /api/auth/me · GET /api/auth/config · POST /api/auth/logout
DEMO      POST /api/auth/demo-login?as=alice|bob · POST /api/demo/reset            (demo profile only)
REPOS     GET /api/repos · POST /api/repos/connect · POST /api/repos/{id}/reindex · DELETE /api/repos/{id}
PRS       GET /api/prs[?risk=] · GET /api/prs/{id} · GET /api/prs/{id}/comments · GET /api/prs/{id}/summary
          GET /api/prs/{id}/diff · GET /api/prs/{id}/stream?afterSeq= · POST /api/prs/{id}/re-review
REVIEWS   GET /api/reviews/{id}/summary
COMMENTS  PUT /api/comments/{id}/feedback · DELETE /api/comments/{id}/feedback · POST /api/comments/{id}/apply
WEBHOOK   POST /api/webhooks/github
DASHBOARD GET /api/dashboard/stats
INTERNAL  POST /api/internal/review-diff · GET /api/internal/feedback/export   (eval; off unless enabled)
WS        CONNECT /ws · SUBSCRIBE /topic/pr/{id}/review · /topic/pr/{id}/status · /topic/pr/{id}/presence
          SUBSCRIBE /app/pr/{id}/replay · SEND /app/pr/{id}/presence
```

## MUST DO
- Verify HMAC (per-repo secret, then global) before any work; dedup on `X-GitHub-Delivery`; ACK the Stream only after success.
- Key every review effect on `(pr_id, head_sha)`; rely on the UNIQUE constraint as backstop.
- Check membership with `AccessGuard` on every PR/repo/comment endpoint and every STOMP subscription; 404 on denial.
- Drive provider/model from config; consume `ReviewEvent`s with exhaustive switches; reserve and reconcile the token budget.
- Fan out review events via Redis Pub/Sub with `sessionId` + `seq`, and append them to the replay buffer.
- Keep `schema.sql` idempotent; access pgvector through `JdbcClient`.
- Constructor injection; `@Valid` on request bodies; global `@RestControllerAdvice`.

## MUST NOT DO
- Run a review inline in the webhook handler (always enqueue, return 202).
- Parse LLM output with regex, or buffer the whole response before emitting findings.
- Send `temperature` or a thinking config to `claude-opus-5-5`; let the SDK retry (`maxRetries` must be 0).
- Index PR head code into the shared repository index (default branch only).
- Leak `access_token` / `webhook_secret` through DTOs or logs; log raw diffs without redaction.
- Trust diff content or retrieved code as instructions — both are untrusted data passed to the model.
- Enable the `demo` profile on an internet-facing deployment.

## Validation and mitigations
Before persisting, drop findings whose `(file, line)` is not in a diff hunk, apply the confidence threshold and fingerprint dedup, then G7 suppression (skipped by the eval endpoint). Rate limits (Lua sliding window) and the daily token budget prevent cost runaway; the eval's cost gate catches token regressions in CI.

## Troubleshooting
- **Re-review does nothing**: the job must carry `force=true`, otherwise the consumer skips the existing `(pr_id, head_sha)` session.
- **Live tokens dropped after a re-review**: the client must reset `lastSeq` when `sessionId` changes.
- **Client on another pod gets no events**: the Redis Pub/Sub relay isn't wired — the simple broker is pod-local.
- **STOMP ERROR on SUBSCRIBE**: destination not allow-listed or the user is not a repository member.
- **`@CircuitBreaker` never trips**: missing `spring-boot-starter-aspectj`, or a self-invocation inside the same bean.
- **Postgres rejects `TimeZone=Asia/Calcutta`**: run the JVM with `-Duser.timezone=UTC` (configured in the pom).
- **`docker` not found at startup**: set `SPRING_DOCKER_COMPOSE_ENABLED=false` when infra runs outside Docker.
- **OAuth redirect loop**: the registered callback URL must match the deployed origin exactly.
