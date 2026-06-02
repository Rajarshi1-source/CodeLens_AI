# CodeLens AI — Real-time Collaborative Code Review Tool
## Complete Implementation Plan v2 (Master) — Junior Full-Stack Developer Interview (2026)

> **This is the merged master document.** It folds the v2 supplement (model update, availability & consistency patterns, resilience, mitigation, MLOps wrapper, deployment strategies, MAANG differentiators) into the original plan, renumbered into one clean flow.

---

## Table of Contents

1. Project Overview & Interview Hook
2. Model Choice — GPT-5 / Current Models *(new in v2)*
3. Tech Stack — Every Choice Justified
4. MVP Blueprint — 6-Week Build Plan
5. High-Level Design (HLD)
6. Detailed System Architecture (Infra Topology) *(new in v2)*
7. Low-Level Design (LLD)
8. Database Design & Choice
9. Availability & Consistency Patterns *(new in v2)*
10. Caching & Messaging — Redis vs Kafka vs RabbitMQ
11. Design Patterns Used
12. Resilience Patterns (Consolidated) *(new in v2)*
13. Mitigation Strategies *(new in v2)*
14. Docker & Kubernetes
15. Deployment Strategies — Rolling / Blue-Green / Canary *(new in v2)*
16. CI/CD Pipeline — GitHub Actions
17. Monitoring & Observability
18. DevOps / MLOps Wrapper *(new in v2)*
19. Differentiator Features for MAANG/FAANG *(new in v2)*
20. README Blueprint
21. Interview Prep — Questions & Answers
22. Deployment Checklist — Go Live
23. Final Word

---

## 1. Project Overview & Interview Hook

**Project Name:** CodeLens AI

**One-liner:** A real-time collaborative code review platform where every GitHub PR gets an AI-powered review in under 30 seconds — reviewers see comments stream word-by-word inline on diffs, exactly like GitHub Copilot.

**Interview Hook (memorize this):**

> "Every PR gets an AI review in under 30 seconds. The LLM streams tokens to my backend over SSE, and my backend relays them to every connected browser over WebSocket — so reviewers see comments appear word-by-word, exactly like Copilot. The system ingests GitHub webhooks idempotently, queues diff analysis through Redis Streams, runs a current frontier model behind a provider-agnostic adapter, and pushes structured comments (critical/warning/suggestion) to all connected clients. I deployed it with Docker Compose, set up a CI/CD pipeline that gates merges on review-quality evals, and made a scaling decision to use Redis Pub/Sub over Kafka because my throughput didn't justify Kafka's operational overhead."

**Why interviewers love this:**
- Event-driven architecture (webhooks → queue → process → push)
- Real-time streaming (LLM SSE → backend → WebSocket fan-out)
- Third-party API integration (GitHub + LLM provider)
- Pragmatic scaling decisions (Redis over Kafka — with reasoning)
- CI/CD + Docker + monitoring + **AI evals** = maturity 95% of freshers lack

> **Precision note for interviews:** The LLM streams to your *backend* via SSE; your backend relays to *browsers* via WebSocket/STOMP. Two transports, one pipeline. Say it that way.

---

## 2. Model Choice — GPT-5 / Current Models

**The original brief said "GPT-4." For a 2026 project that's dated.** Default to a current frontier model (GPT-5-class, e.g. a GPT-5 Codex variant, or Claude Sonnet 4.6 / Opus 4.7-class). They beat GPT-4 on the three things that matter for code review:

1. **Cross-file reasoning** — catching a bug whose cause is in a different file than the diff.
2. **Reliable structured output** — emitting `{file, line, severity, comment, confidence}` JSON without drift.
3. **Longer context** — reviewing large diffs without aggressive truncation.

**But the senior move is to never hardcode a vendor.** Put the model behind an adapter and make it config-driven. Interviewers respect "I built a model-agnostic layer so I can swap providers when pricing or quality shifts" far more than "I used GPT-5."

### Recommended model strategy (tiered)

| Task | Model tier | Why |
|---|---|---|
| **PR review (the core)** | Frontier model (GPT-5-class / Claude Opus/Sonnet 4.x) | Quality matters most; this is the product |
| **PR summary + risk score** | Mid-tier model | Summarization is easier; save cost |
| **Comment dedup / severity calibration** | Small/cheap model or pure heuristics | Doesn't need a frontier model |

### The adapter (replaces direct OpenAI calls)

```java
public interface LlmReviewProvider {
    /** Streams structured review comments for a diff chunk. */
    Flux<ReviewToken> streamReview(ReviewPromptContext ctx);
    String providerId();
}
// Implementations: OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider
// Selected via application.yml: codelens.llm.provider = gpt5 | claude | local
```

```yaml
# application.yml — model is config, not code
codelens:
  llm:
    provider: ${LLM_PROVIDER:gpt5}       # swap without recompiling
    review-model: ${REVIEW_MODEL:gpt-5}  # current frontier model id at build time
    summary-model: ${SUMMARY_MODEL:gpt-5-mini}
    temperature: 0.1                      # low for consistent, reproducible reviews
    max-output-tokens: 2000
    fallback-provider: claude             # circuit-breaker fallback (see §12)
```

**Structured output:** use the provider's JSON/structured-output mode (or tool-calling) so each comment is schema-validated, not regex-parsed from prose. Stream partial JSON per comment so the UI renders each comment as it completes.

> **Interview line:** *"I kept the model behind an adapter driven by config. I default to a current frontier model for review quality, a cheaper model for summaries, and heuristics for dedup. If pricing spikes or another model pulls ahead on code, I change one env var."*

---

## 3. Tech Stack — Every Choice Justified

### Core Stack

| Layer | Technology | Why This (Interview Answer) |
|---|---|---|
| **Frontend** | React 18 + TypeScript | Industry standard for SPAs. TS catches bugs at compile time. React's component model fits diff views and streaming comments. |
| **UI Library** | Tailwind CSS + shadcn/ui | Rapid prototyping; accessible, production-grade components without heavy bundle. |
| **Diff Rendering** | react-diff-viewer-continued | Purpose-built for unified/split diffs with syntax highlighting. Saves 2+ weeks. |
| **Backend** | Spring Boot 3.x (Java 21) | Enterprise-grade, dominant in Bangalore product companies. Built-in WebSocket, virtual threads for concurrent LLM calls. |
| **Real-time** | WebSocket (STOMP over SockJS) | Bidirectional, low-latency. STOMP gives topic routing (per PR). SockJS falls back behind corporate proxies. |
| **AI Integration** | Current frontier LLM behind an adapter (streaming) — see §2 | Streaming sends tokens as SSE to the backend, relayed over WebSocket to all clients. |
| **Database** | PostgreSQL 16 | See §8. Relational integrity for PRs/comments/users, JSONB for AI metadata, full-text search. |
| **Cache** | Redis 7 | See §10. Session cache, WebSocket Pub/Sub for scaling, rate limiting, idempotency keys. |
| **Message Queue** | Redis Streams (not Kafka) | See §10. At MVP throughput, Streams give queue semantics without Kafka overhead. |
| **Version Control** | GitHub API v3 + Webhooks | Webhooks push PR events; REST fetches diffs and posts comments. |
| **Containerization** | Docker + Docker Compose | Single-command local dev; multi-service orchestration. |
| **CI/CD** | GitHub Actions | Free for public repos; native Docker build/push; deploy to EC2 or Railway. |
| **Monitoring** | Prometheus + Grafana | Spring Boot Actuator exposes metrics; Grafana dashboards for latency, queue depth, errors. |
| **LLM Observability** | Langfuse (self-hostable) | Trace every review: prompt version, tokens, cost, latency. See §18. |

### Why NOT These Alternatives (Interview Ammo)

| Rejected Option | Why |
|---|---|
| Node.js/Express backend | Spring Boot has stronger WebSocket support, type safety, and is preferred by Razorpay/Flipkart/Atlassian India. |
| MongoDB | Highly relational data (users → repos → PRs → comments → reviews). Mongo forces denormalization and loses JOINs. |
| Kafka | Operational overhead (brokers, partition management) unjustified for < 1000 events/min. Redis Streams gives 80% of value at 10% complexity. |
| GraphQL | REST is simpler; predictable data shapes; GraphQL adds complexity without benefit here. |
| Next.js | No SSR need — this is a dashboard app. Pure React SPA is simpler and faster to build. |
| Hardcoding GPT-4 | Vendor lock-in; the adapter (§2) keeps the model swappable. |

---

## 4. MVP Blueprint — 6-Week Build Plan

### MVP Scope (What to Build)

**Must Have (MVP):**
- GitHub OAuth login
- Connect GitHub repositories via webhook
- View list of PRs with status (pending review / reviewed)
- Click a PR → see side-by-side diff
- AI auto-reviews each new PR (current frontier model analyzes diff)
- AI comments appear in real-time on the diff (streamed via WebSocket)
- Each comment has severity: CRITICAL / WARNING / SUGGESTION
- **Idempotent webhook handling** (GitHub delivers duplicates — see §9)
- Comments persisted in PostgreSQL
- Basic dashboard showing review stats

**Nice to Have (Post-MVP):**
- Manual human comments alongside AI comments
- Re-review button (re-run AI on updated PR)
- Slack notifications when review completes
- Review summary email
- Multi-repo dashboard; dark mode

### Week-by-Week Schedule

**Week 1 — Foundation & Auth**
- Day 1–2: Spring Boot + PostgreSQL + Redis via Docker Compose
- Day 3–4: GitHub OAuth2 login (Spring Security OAuth2 Client)
- Day 5–6: User model, JWT session, Redis session cache
- Day 7: React scaffold, Tailwind + shadcn/ui, login page
- Deliverable: Log in with GitHub, see profile

**Week 2 — GitHub Integration**
- Day 1–2: GitHub App registration, webhook endpoint (`/api/webhooks/github`) **with idempotency** (§9)
- Day 3–4: Parse PR events, store PR metadata
- Day 5–6: Fetch PR diff via GitHub API, store in DB
- Day 7: React repo connection page
- Deliverable: System receives PR webhooks (deduped) and stores PR data

**Week 3 — AI Review Engine**
- Day 1–2: LLM adapter + streaming (SSE) (§2)
- Day 3–4: Diff chunking strategy
- Day 5: Prompt engineering — structured output
- Day 6–7: Review job processor (idempotent consumer, §9)
- Deliverable: AI generates structured review comments

**Week 4 — Real-time Streaming**
- Day 1–2: WebSocket (STOMP + SockJS), topic per PR
- Day 3–4: Relay LLM tokens through WebSocket
- Day 5–6: React diff viewer with inline comment overlay
- Day 7: Comment component (severity badge, streaming text, line anchor)
- Deliverable: Open a PR → AI comments stream in real-time

**Week 5 — Dashboard & Polish**
- Day 1–2: PR list page (status badges, filters, search)
- Day 3–4: Review detail page (diff + comments + severity summary)
- Day 5: Dashboard (review count, avg time, severity chart)
- Day 6–7: Error handling, loading/empty states, responsive
- Deliverable: Complete user-facing application

**Week 6 — DevOps, MLOps & Deployment**
- Day 1–2: Dockerfiles (multi-stage)
- Day 3: docker-compose.yml (all services)
- Day 4: GitHub Actions CI/CD → DockerHub → EC2/Railway; **add eval gate (§18)**
- Day 5: Prometheus + Grafana + Langfuse
- Day 6: README with architecture diagram, screenshots
- Day 7: Deploy publicly, end-to-end test, demo video
- Deliverable: Live deployed app with CI/CD + monitoring + eval gate

---

## 5. High-Level Design (HLD)

### Architecture Overview

```
┌──────────────┐     Webhook (PR events)     ┌──────────────────┐
│   GitHub      │ ──────────────────────────→ │  Spring Boot     │
│   (Repos)     │                             │  Backend         │
│               │ ←── GitHub API (post        │                  │
└──────────────┘      comments, fetch diff)   │  ┌────────────┐  │
                                              │  │ Webhook    │  │
┌──────────────┐     WebSocket (STOMP)        │  │ Controller │  │
│   React SPA  │ ←──────────────────────────→ │  ├────────────┤  │
│   (Browser)  │                              │  │ Review     │  │
│              │     REST API (CRUD)          │  │ Service    │  │
│  - Diff View │ ←──────────────────────────→ │  ├────────────┤  │
│  - Dashboard │                              │  │ WebSocket  │  │
│  - PR List   │                              │  │ Handler    │  │
└──────────────┘                              │  ├────────────┤  │
                                              │  │ GitHub     │  │
┌──────────────┐     LLM Streaming (SSE)      │  │ Service    │  │
│  LLM Provider│ ←──────────────────────────→ │  ├────────────┤  │
│  (adapter)   │                              │  │ AI Service │  │
└──────────────┘                              │  └────────────┘  │
                                              └────────┬─────────┘
                                                       │
                                              ┌────────┴─────────┐
                                     ┌────────┴──┐     ┌────────┴──┐
                                     │PostgreSQL │     │  Redis     │
                                     │(primary   │     │(cache,     │
                                     │ storage)  │     │ pub/sub,   │
                                     └───────────┘     │ queue)     │
                                                       └───────────┘
```

### Request Flow — PR Webhook to Real-time AI Review

```
Step 1: Developer pushes code → GitHub fires webhook (pull_request event)
Step 2: Spring Boot Webhook Controller receives POST /api/webhooks/github
Step 3: Validate HMAC-SHA256 signature → dedup on X-GitHub-Delivery (§9) → parse PR metadata
Step 4: Enqueue review job in Redis Streams (key: "review-jobs")
Step 5: Review Worker picks up job → calls GitHub API to fetch PR diff
Step 6: Diff Chunker splits diff into reviewable segments (< 4000 tokens each)
Step 7: For each chunk → call LLM provider (adapter) in STREAMING mode
Step 8: As the LLM streams tokens → backend relays each token via WebSocket
         to topic /topic/pr/{prId}/review (cross-pod via Redis Pub/Sub)
Step 9: React client receives tokens → renders comments word-by-word inline
Step 10: When stream completes → persist full comment to PostgreSQL (idempotent, §9)
Step 11: Optionally → post summary comment on GitHub PR
```

### Component Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    FRONTEND (React SPA)                  │
│  ┌───────────┐  ┌───────────┐  ┌───────────────────┐    │
│  │ Auth      │  │ Dashboard │  │ PR Review View    │    │
│  │ (OAuth)   │  │ (Stats)   │  │  Diff + AI        │    │
│  └───────────┘  └───────────┘  │  Comments (stream)│    │
│  ┌───────────┐  ┌───────────┐  └───────────────────┘    │
│  │ Repo      │  │ PR List   │                           │
│  │ Settings  │  │           │  Services: WS Client,     │
│  └───────────┘  └───────────┘  REST Client, Zustand     │
└─────────────────────────────────────────────────────────┘
                    REST + WebSocket
┌─────────────────────────────────────────────────────────┐
│                  BACKEND (Spring Boot)                   │
│  Auth │ Webhook │ Review │ WebSocket │ GitHub │ AI │ Analytics │
│  Cross-cutting: Security, Logging, Metrics, Rate Limiting│
└─────────────────────────────────────────────────────────┘
        ┌────────────────┼────────────────┐
  ┌─────┴─────┐   ┌─────┴─────┐   ┌──────┴──────┐
  │ PostgreSQL│   │   Redis   │   │ LLM Provider│
  └───────────┘   └───────────┘   └─────────────┘
```

---

## 6. Detailed System Architecture (Infra Topology)

The HLD (§5) shows *logic flow*. This shows *deployment topology and scaling units*.

```
                          ┌──────────────────────────────────────┐
   GitHub  ───webhook────►│         INGRESS / LB (nginx)          │
   (PR events)            │         TLS termination               │
                          └───────────────┬──────────────────────┘
              ┌───────────────────────────┼───────────────────────────┐
              ▼                           ▼                           ▼
     ┌─────────────────┐        ┌─────────────────┐        ┌──────────────────┐
     │  backend pod #1 │        │  backend pod #2 │        │  frontend pods    │
     │  Spring Boot    │        │  Spring Boot    │        │  (React static    │
     │  • webhook ctrl │        │  • webhook ctrl │        │   via nginx/CDN)  │
     │  • WS endpoint  │        │  • WS endpoint  │        └──────────────────┘
     │  • review worker│        │  • review worker│
     └────────┬────────┘        └────────┬────────┘
              │  (WS connections sticky to one pod;
              │   cross-pod fan-out via Redis Pub/Sub)
              └──────────┬───────────────┘
        ┌────────────────┼─────────────────────────────────┐
        ▼                ▼                                  ▼
┌────────────────┐ ┌──────────────────┐          ┌────────────────────┐
│ Redis          │ │ PostgreSQL 16    │          │ LLM Provider        │
│ • Pub/Sub      │ │  (primary)       │          │ (via adapter)       │
│   (WS fan-out) │ │      │           │          │ + Langfuse tracing  │
│ • Streams      │ │      ▼           │          └────────────────────┘
│   (job queue)  │ │  read replica    │
│ • cache        │ │  (dashboards)    │
└────────────────┘ └──────────────────┘

SCALING UNITS:
  • backend       — stateless except WS connections → HPA on CPU + active WS count
  • review worker — can be split into its own deployment for independent scaling
  • frontend      — static, CDN-served; scales infinitely
  • postgres      — vertical + 1 read replica for dashboard queries
  • redis         — single node MVP; Sentinel/Cluster at scale

NETWORK ZONES:
  • public:  ingress, frontend
  • app:     backend, review workers
  • data:    postgres, redis (no public ingress; NetworkPolicy-restricted)
```

**Key decision to articulate:** *WebSocket connections are stateful and sticky to a pod, but review jobs are stateless and queued in Redis Streams. So I scale the WS layer on connection count and the worker layer on queue depth — independently. The Redis Pub/Sub bus means a token produced on pod #1 still reaches a client connected to pod #2.*

---

## 7. Low-Level Design (LLD)

### 7.1 Backend Package Structure

```
src/main/java/com/codelensai/
├── CodelensAiApplication.java
├── config/
│   ├── SecurityConfig.java          # OAuth2 + JWT
│   ├── WebSocketConfig.java         # STOMP + SockJS
│   ├── RedisConfig.java             # Redis template + pub/sub
│   ├── LlmConfig.java               # adapter selection, model, timeout (§2)
│   └── GitHubConfig.java            # App ID, webhook secret
├── controller/
│   ├── AuthController.java
│   ├── WebhookController.java       # /api/webhooks/github (idempotent, §9)
│   ├── PullRequestController.java
│   ├── ReviewController.java
│   └── DashboardController.java
├── websocket/
│   ├── ReviewStreamHandler.java     # Relays AI tokens to clients
│   └── WebSocketEventListener.java
├── service/
│   ├── GitHubService.java
│   ├── ReviewService.java           # Orchestrates review workflow
│   ├── AIReviewService.java         # LLM streaming via adapter
│   ├── DiffChunkerService.java
│   ├── ReviewJobConsumer.java       # Redis Streams consumer (idempotent)
│   ├── IdempotencyService.java      # Redis SETNX dedup (§9)
│   └── WebSocketNotifier.java
├── model/ (entity / dto / enums)
├── repository/
├── exception/
└── util/
    ├── WebhookSignatureValidator.java
    ├── DiffParser.java
    └── PromptBuilder.java
```

### 7.2 Frontend Structure

```
src/
├── routes/ (Login, Dashboard, RepoSettings, PRList, PRReview)
├── components/
│   ├── diff/ (DiffViewer, InlineComment, SeverityBadge, StreamingText)
│   ├── pr/ (PRCard, PRStatusBadge, PRFilters)
│   ├── dashboard/ (StatsCard, SeverityChart, ReviewTimeline)
│   └── common/ (LoadingSpinner, ErrorBoundary, EmptyState)
├── hooks/
│   ├── useWebSocket.ts              # STOMP connection manager
│   ├── useReviewStream.ts           # subscribe to /topic/pr/{id}/review
│   ├── useAuth.ts
│   └── usePullRequests.ts
├── services/ (api, authService, prService, websocketService)
├── store/ (Zustand)
└── types/
```

### 7.3 Key API Endpoints

```
AUTH         POST /api/auth/github/callback · GET /api/auth/me · POST /api/auth/logout
REPOS        GET /api/repos · POST /api/repos/connect · DELETE /api/repos/{id}
PULL REQS    GET /api/prs · GET /api/prs/{id} · POST /api/prs/{id}/re-review
REVIEWS      GET /api/prs/{id}/comments · GET /api/reviews/{id}/summary
WEBHOOKS     POST /api/webhooks/github
DASHBOARD    GET /api/dashboard/stats
WEBSOCKET    CONNECT /ws · SUBSCRIBE /topic/pr/{id}/review · /topic/pr/{id}/status
```

### 7.4 Core Service — AI Review Streaming Flow (Pseudocode)

```java
@Service
public class ReviewService {
    public void processReview(Long prId, String headSha) {
        // Idempotency: skip if this commit SHA was already reviewed (§9)
        if (reviewSessionRepo.existsByPrIdAndHeadSha(prId, headSha)) return;

        pullRequestRepo.updateStatus(prId, ReviewStatus.IN_PROGRESS);
        webSocketNotifier.sendStatus(prId, "IN_PROGRESS");

        String diff = gitHubService.fetchPRDiff(prId);
        List<DiffChunk> chunks = diffChunkerService.chunk(diff, MAX_TOKENS_PER_CHUNK);

        for (DiffChunk chunk : chunks) {
            ReviewPromptContext ctx = promptBuilder.build(chunk);
            llmProvider.streamReview(ctx).subscribe(token ->
                webSocketNotifier.sendToken(prId, ReviewStreamToken.of(
                    chunk.getFileName(), chunk.getStartLine(), token)));
        }

        List<AIReviewComment> comments = aiReviewService.parseComments();
        comments = validateLineNumbers(comments, diff);   // drop hallucinated lines (§13)

        // Idempotent upsert keyed on (pr_id, head_sha)
        reviewCommentRepo.upsertForSession(prId, headSha, comments);
        gitHubService.postReviewSummary(prId, comments);

        pullRequestRepo.updateStatus(prId, ReviewStatus.COMPLETED);
        webSocketNotifier.sendStatus(prId, "COMPLETED");
    }
}
```

### 7.5 WebSocket Configuration

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");   // Redis-backed for horizontal scaling
        config.setApplicationDestinationPrefixes("/app");
    }
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins("*").withSockJS();
    }
}
```

### 7.6 Frontend — Streaming Comment Hook

```typescript
export function useReviewStream(prId: string) {
  const [comments, setComments] = useState<StreamingComment[]>([]);
  const [status, setStatus] = useState<ReviewStatus>('PENDING');

  useEffect(() => {
    const client = getStompClient();
    client.subscribe(`/topic/pr/${prId}/review`, (message) => {
      const token: ReviewStreamToken = JSON.parse(message.body);
      setComments(prev => {
        const existing = prev.find(c => c.file === token.file && c.line === token.line);
        if (existing) {
          return prev.map(c => c === existing ? { ...c, text: c.text + token.text } : c);
        }
        return [...prev, { file: token.file, line: token.line,
                           severity: token.severity, text: token.text, isStreaming: true }];
      });
    });
    client.subscribe(`/topic/pr/${prId}/status`, (m) => setStatus(m.body as ReviewStatus));
    return () => client.unsubscribe();
  }, [prId]);

  return { comments, status };
}
```

---

## 8. Database Design & Choice

### 8.1 Comparison Matrix — Which Database?

| Criteria | PostgreSQL | MongoDB | Cassandra | TimescaleDB | CosmosDB |
|---|---|---|---|---|---|
| **Data Model** | Relational | Document | Wide-column | Time-series ext. of PG | Multi-model |
| **Relationships** | Native JOINs, FKs | Manual $lookup | No JOINs | Same as PG | Depends on API |
| **ACID** | Full | Single-doc only | Eventual | Full | Configurable |
| **Query Flexibility** | Full SQL | No JOINs | Partition-key bound | SQL + time fns | SQL-like |
| **JSONB** | Excellent | Native | No | Same as PG | Native |
| **Full-text** | Built-in (tsvector) | $text | No | Same as PG | No |
| **Horizontal Scaling** | Replicas | Auto-shard | Massive | Same as PG | Auto |
| **Operational Cost** | Low | Medium | High (3+ nodes) | Low | High (pay-per-RU) |
| **Best For** | Relational + some flexibility | Schema-less | Write-heavy massive | Time-series | Global distribution |

### VERDICT: PostgreSQL 16

1. **Inherently relational:** Users → Repos → PRs → ReviewSessions → Comments. Maps perfectly to FKs + JOINs.
2. **JSONB for AI metadata:** Variable LLM response structure stored flexibly, still indexable.
3. **Full-text search:** tsvector handles comment search without Elasticsearch.
4. **ACID:** Review completion atomically updates PR status + comments + stats — no ghost states.
5. **Operational simplicity:** Single instance handles MVP scale.

**Why NOT the others:** MongoDB loses JOINs; Cassandra is built for millions of writes/sec (we do ~100/hour); TimescaleDB only wins if primary queries were time-series (ours are relational); CosmosDB is Azure-locked and pay-per-request.

### 8.2 Schema Design (PostgreSQL)

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    github_id BIGINT UNIQUE NOT NULL,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    avatar_url VARCHAR(500),
    access_token VARCHAR(255) NOT NULL,         -- encrypted at rest
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE repositories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    github_repo_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    webhook_id BIGINT,
    webhook_secret VARCHAR(255),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, github_repo_id)
);

CREATE TABLE pull_requests (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT REFERENCES repositories(id) ON DELETE CASCADE,
    github_pr_id BIGINT NOT NULL,
    pr_number INT NOT NULL,
    title VARCHAR(500) NOT NULL,
    author VARCHAR(100) NOT NULL,
    branch_from VARCHAR(255),
    branch_to VARCHAR(255),
    head_sha VARCHAR(40),                        -- for idempotency (§9)
    status VARCHAR(20) DEFAULT 'PENDING',
    diff_url VARCHAR(1000),
    html_url VARCHAR(1000),
    files_changed INT DEFAULT 0,
    additions INT DEFAULT 0,
    deletions INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(repo_id, github_pr_id)
);
CREATE INDEX idx_pr_status ON pull_requests(status);
CREATE INDEX idx_pr_repo ON pull_requests(repo_id);

CREATE TABLE review_sessions (
    id BIGSERIAL PRIMARY KEY,
    pr_id BIGINT REFERENCES pull_requests(id) ON DELETE CASCADE,
    head_sha VARCHAR(40) NOT NULL,               -- idempotency key (§9)
    status VARCHAR(20) DEFAULT 'IN_PROGRESS',
    model_used VARCHAR(50) DEFAULT 'gpt-5',      -- model-agnostic; logs actual model
    prompt_version VARCHAR(20),                  -- which prompt produced this (§18)
    total_tokens INT DEFAULT 0,
    cost_usd NUMERIC(10,6) DEFAULT 0,            -- per-review cost (§18)
    review_time_ms INT DEFAULT 0,
    summary TEXT,
    metadata JSONB DEFAULT '{}',
    started_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    UNIQUE(pr_id, head_sha)                       -- effectively-once reviews
);
CREATE INDEX idx_session_pr ON review_sessions(pr_id);

CREATE TABLE review_comments (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT REFERENCES review_sessions(id) ON DELETE CASCADE,
    pr_id BIGINT REFERENCES pull_requests(id) ON DELETE CASCADE,
    file_path VARCHAR(1000) NOT NULL,
    line_number INT NOT NULL,
    severity VARCHAR(20) NOT NULL,
    comment_text TEXT NOT NULL,
    code_suggestion TEXT,
    confidence NUMERIC(4,3),                      -- model confidence (§19)
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_comment_pr ON review_comments(pr_id);
CREATE INDEX idx_comment_severity ON review_comments(severity);
CREATE INDEX idx_comment_search ON review_comments
    USING GIN (to_tsvector('english', comment_text));

-- Feedback flywheel (§18)
CREATE TABLE comment_feedback (
    id BIGSERIAL PRIMARY KEY,
    comment_id BIGINT REFERENCES review_comments(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id),
    vote SMALLINT NOT NULL,                       -- -1 dismiss / +1 helpful
    created_at TIMESTAMP DEFAULT NOW()
);
```

### 8.3 Key Queries

```sql
-- All comments for a PR with session metadata
SELECT rc.*, rs.model_used, rs.review_time_ms
FROM review_comments rc JOIN review_sessions rs ON rc.session_id = rs.id
WHERE rc.pr_id = ? ORDER BY rc.file_path, rc.line_number;

-- Dashboard: severity distribution for a user's repos
SELECT rc.severity, COUNT(*) FROM review_comments rc
JOIN pull_requests pr ON rc.pr_id = pr.id
JOIN repositories r ON pr.repo_id = r.id
WHERE r.user_id = ? GROUP BY rc.severity;

-- Comment search
SELECT * FROM review_comments
WHERE pr_id = ? AND to_tsvector('english', comment_text) @@ plainto_tsquery(?);

-- Avg + p95 review time
SELECT AVG(review_time_ms),
       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY review_time_ms)
FROM review_sessions WHERE completed_at > NOW() - INTERVAL '24 hours';
```

---

## 9. Availability & Consistency Patterns

A top-tier interview topic — and it's also where the original plan had a real correctness gap (idempotency).

### 9.1 CAP positioning — two consistency regimes

| Path | Regime | Why |
|---|---|---|
| **Live comment stream** (WS push) | **AP** | A reviewer prefers a slightly-delayed comment over a frozen UI. Eventual consistency is fine. |
| **Persisted review state** (Postgres) | **CP** | Review completion must commit PR status + comments + stats atomically. No ghost states. |

> **Interview gold:** *"I treat the system as two consistency planes. The live stream is AP — availability over consistency, because a reviewer prefers a slightly-delayed comment to a frozen UI. The persisted state is CP — review completion is one ACID transaction, so we never show a half-saved review. Recognizing one product has two regimes is CAP in practice."*

### 9.2 Idempotent webhook processing (CRITICAL)

**GitHub delivers webhooks at-least-once — the same event can arrive 2–3 times.** Without dedup you'd run (and pay for) duplicate reviews and emit duplicate comments.

```java
@PostMapping("/api/webhooks/github")
public ResponseEntity<?> handle(@RequestHeader("X-GitHub-Delivery") String deliveryId,
                                @RequestHeader("X-Hub-Signature-256") String signature,
                                @RequestBody String payload) {
    verifyHmacSignature(payload, signature);          // reject forgeries
    Boolean isNew = redis.opsForValue()
        .setIfAbsent("webhook:seen:" + deliveryId, "1", Duration.ofHours(24));  // SETNX
    if (Boolean.FALSE.equals(isNew)) return ResponseEntity.ok("duplicate ignored");
    enqueueReviewJob(payload);
    return ResponseEntity.accepted().build();
}
```

Make the **review write idempotent** too: key review sessions on `(pr_id, head_sha)`. Re-processing the same SHA upserts, never duplicates.

### 9.3 At-least-once delivery + idempotent consumer = effectively-once

Redis Streams is at-least-once (a worker can crash after read, before ack). So the *effect* must be idempotent: a review for `(pr_id, head_sha)` that exists is replaced, not appended. Say exactly: *"at-least-once delivery plus an idempotent consumer gives effectively-once processing."*

### 9.4 Read-your-writes

Dashboard reads hit the Postgres **read replica** (eventual, ms lag). But the user who just triggered a review must see their result immediately → route that user's reads to the primary briefly after their write. Prevents "I clicked re-review but nothing changed."

### 9.5 Resumable WebSocket stream (missed-message recovery)

```
On WS reconnect for PR {id}:
  1. Client sends last-seen comment sequence number.
  2. Server replays missed comments from the Redis Stream (or Postgres) since that seq.
  3. Resumes live push.
```

Sequence numbers per review session make the AP stream a guarantee that the reviewer *eventually* sees every comment.

### 9.6 Concurrent human + AI comments

Optimistic concurrency (version / `updated_at` check), not locks — comments are append-only and rarely truly conflict, so optimistic is cheaper and avoids contention.

### 9.7 Availability tactics

- **Postgres:** primary + 1 read replica; automated failover (Patroni / managed RDS Multi-AZ).
- **Redis:** Sentinel at scale (single node acceptable for MVP — a conscious trade-off).
- **Stateless backend:** ≥2 replicas; a pod death only drops that pod's WS connections, which reconnect (§9.5).
- **Graceful degradation:** LLM down → PR still appears with status `REVIEW_UNAVAILABLE`; the app works without AI comments.

---

## 10. Caching & Messaging — Redis vs Kafka vs RabbitMQ

### 10.1 Comparison for THIS Project

| Criteria | Redis (Streams + Pub/Sub) | Kafka | RabbitMQ | ZooKeeper |
|---|---|---|---|---|
| **Primary Purpose** | Cache + lightweight messaging | Distributed event log | Message broker | Coordination (NOT a queue) |
| **Throughput** | ~100K msg/sec (1 node) | Millions/sec | ~50K msg/sec | N/A |
| **Persistence** | Optional (Streams persist) | Always | Optional | N/A |
| **Operational Complexity** | Very low (1 binary) | High (brokers + KRaft) | Medium (Erlang) | Used BY Kafka |
| **Our Fit** | Cache + WS Pub/Sub + queue = one tool | Overkill < 1000/min | Good but separate from cache | Not applicable |

### VERDICT: Redis 7 (Streams + Pub/Sub)

**Three uses in CodeLens:**

**1 — Session & data cache + idempotency**
```
session:{userId}          → JWT + profile (TTL 24h)
pr:diff:{prId}            → cached diff (TTL 1h, avoids re-fetch)
webhook:seen:{deliveryId} → idempotency key (TTL 24h)  ← §9
rate:llm:global           → outbound LLM rate limiter
```

**2 — WebSocket horizontal scaling (Pub/Sub)**
```
Server A receives a token → publishes to "review:{prId}"
Server B (holds the client) → subscribes → pushes to client
Standard pattern for Socket.IO / Spring WebSocket.
```

**3 — Review job queue (Redis Streams)**
```
Producer: XADD review-jobs * prId 42 headSha abc...
Consumer: XREADGROUP GROUP reviewers worker-1 COUNT 1 BLOCK 5000 STREAMS review-jobs >
Ack:      XACK review-jobs reviewers {messageId}
Gives consumer groups, acknowledgment, backpressure (BLOCK), XPENDING visibility.
```

**Why NOT Kafka?** Min 3 brokers + KRaft for production — 4GB+ RAM for ~100 events/hour. Redis does the same in a 50MB process. *"I'd migrate to Kafka if we scaled to 10K+ PRs/hour with multiple independent consumers needing replay — a scaling decision, not day-one."*

**Why NOT RabbitMQ?** We already need Redis for cache + Pub/Sub. Adding RabbitMQ means a separate Erlang service for one feature Streams handles natively.

**Why NOT ZooKeeper?** It's a coordination service, not a queue — used by old Kafka for metadata/leader election. You wouldn't use it directly in app code. Explaining this distinction signals distributed-systems understanding.

---

## 11. Design Patterns Used

| Pattern | Where | Why |
|---|---|---|
| **Observer / Pub-Sub** | WebSocket topics | Decouples review process from client connections |
| **Strategy** | Diff chunking (code vs markdown vs config) | New strategies without modifying existing (OCP) |
| **Builder** | Prompt construction | Avoids telescoping constructors for complex prompts |
| **Template Method** | Webhook processing | Base handler defines skeleton; subclasses implement event handling |
| **Adapter** | LLM provider, DB dialect | Swap providers via config (§2) |
| **Circuit Breaker** | LLM + GitHub API calls | Fail fast on outage → fallback (§12) |
| **Repository** | Spring Data JPA | Business logic doesn't know SQL/JPA |
| **DTO** | API boundaries | Hide `access_token`, `webhook_secret` from responses |
| **Event-Driven Architecture** | Overall | webhook → Stream job → AI → WS events → UI |

Sample (Builder):
```java
String prompt = new PromptBuilder()
    .withSystemContext("You are a senior code reviewer...")
    .withDiffChunk(chunk)
    .withRetrievedContext(relatedFiles)   // §19 repo-aware context
    .withOutputFormat("JSON array of {file, line, severity, comment, confidence}")
    .withLanguageHint(detectedLanguage)
    .build();
```

---

## 12. Resilience Patterns (Consolidated)

| Pattern | Where | Implementation |
|---|---|---|
| **Circuit breaker** | LLM provider | Resilience4j; open at 50% failure over 20 calls; fall back to secondary provider (§2) |
| **Retry + backoff + jitter** | LLM 429s, GitHub blips | Max 3; respect `Retry-After`; jitter avoids thundering herd |
| **Timeout** | Every external call | LLM 60s, GitHub 10s; fail the chunk, not the whole review |
| **Bulkhead** | LLM vs GitHub pools | Separate Resilience4j bulkheads; Java 21 virtual threads help |
| **Rate limiter** | Outbound LLM + GitHub | Redis sliding-window; queue excess in Streams |
| **Dead-letter queue** | Failed review jobs | After N retries → `review-jobs-dlq` + alert; PR marked `REVIEW_FAILED` with retry button |
| **Graceful degradation** | LLM outage | PR shows `REVIEW_UNAVAILABLE`; auto-retry on recovery |
| **Idempotency** | Webhooks + jobs | Delivery-ID dedup + `(pr_id, head_sha)` upsert (§9) |
| **Backpressure** | Diff queue | Streams `BLOCK` + bounded concurrency; shed/slow acks if queue deep |
| **Health/readiness probes** | K8s | liveness + readiness; readiness false while draining WS |
| **Chunk-level isolation** | Large diffs | One bad chunk fails alone; the rest still completes |

> **Interview gold:** *"Failed review jobs go to a dead-letter stream after three retries so they're never silently lost — the PR shows REVIEW_FAILED with a retry button. And because GitHub delivers webhooks more than once and Redis Streams is at-least-once, my consumer is idempotent keyed on (pr_id, head_sha): at-least-once delivery + idempotent consumer = effectively-once."*

---

## 13. Mitigation Strategies

| Risk | Mitigation |
|---|---|
| **LLM provider outage** | Circuit breaker → fallback provider; degrade to `REVIEW_UNAVAILABLE`; auto-retry |
| **Cost runaway** (huge repo) | Per-repo/user rate limits; daily token budget with hard cap; cheaper model for summaries; cache by `(pr_id, head_sha)` |
| **Webhook flood / DoS** | HMAC verification; per-repo rate limit; queue + backpressure |
| **Huge diffs blow context** | AST-aware chunking (§19); skip generated/vendored files; cap files with a notice |
| **Prompt injection in code** | Treat diff as untrusted data, not instructions; system-prompt isolation; comments are data, never commands |
| **Secret leakage** | Encrypt tokens at rest; don't log diffs with secrets; redact secret patterns before sending to LLM |
| **Hallucinated line numbers** | Validate every `(file, line)` against actual diff hunks; drop invalid comments |
| **Duplicate/noisy comments** | Semantic dedup; confidence threshold to collapse low-confidence comments |
| **WebSocket connection storms** | Per-user connection limits; sticky sessions + Redis fan-out; client reconnect backoff |
| **GitHub API rate limits** | Cache diffs (1h TTL); ETags; per-user token rate limiting |
| **DB connection exhaustion** | HikariCP bounded pool; PgBouncer at scale; separate pool for replica reads |
| **Demo fails at interview** | Pre-recorded backup video; a seeded demo repo + PR that always works offline |

---

## 14. Docker & Kubernetes

### 14.1 Dockerfiles

**Backend (multi-stage):**
```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon
COPY src/ src/
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Frontend (multi-stage):**
```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:80 || exit 1
```

### 14.2 docker-compose.yml (local + single server)

```yaml
version: '3.8'
services:
  frontend:
    build: ./frontend
    ports: ["3000:80"]
    depends_on: [backend]
    environment:
      - VITE_API_URL=http://localhost:8080
      - VITE_WS_URL=ws://localhost:8080/ws
  backend:
    build: ./backend
    ports: ["8080:8080"]
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/codelensai
      - SPRING_DATASOURCE_USERNAME=postgres
      - SPRING_DATASOURCE_PASSWORD=postgres
      - SPRING_REDIS_HOST=redis
      - GITHUB_APP_ID=${GITHUB_APP_ID}
      - GITHUB_WEBHOOK_SECRET=${GITHUB_WEBHOOK_SECRET}
      - LLM_PROVIDER=${LLM_PROVIDER:-gpt5}
      - LLM_API_KEY=${LLM_API_KEY}
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
  postgres:
    image: postgres:16-alpine
    ports: ["5432:5432"]
    environment:
      - POSTGRES_DB=codelensai
      - POSTGRES_USER=postgres
      - POSTGRES_PASSWORD=postgres
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./backend/src/main/resources/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
    volumes: [redis_data:/data]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
  prometheus:
    image: prom/prometheus:latest
    ports: ["9090:9090"]
    volumes: ["./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml"]
  grafana:
    image: grafana/grafana:latest
    ports: ["3001:3000"]
    environment: [GF_SECURITY_ADMIN_PASSWORD=admin]
    volumes:
      - grafana_data:/var/lib/grafana
      - ./monitoring/grafana/dashboards:/etc/grafana/provisioning/dashboards
      - ./monitoring/grafana/datasources:/etc/grafana/provisioning/datasources
  langfuse:
    image: langfuse/langfuse:latest
    ports: ["3002:3000"]
    depends_on: [postgres]
volumes: { postgres_data: , redis_data: , grafana_data: }
```

### 14.3 Kubernetes (reference)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: codelensai-backend }
spec:
  replicas: 2
  selector: { matchLabels: { app: codelensai-backend } }
  template:
    metadata: { labels: { app: codelensai-backend } }
    spec:
      containers:
        - name: backend
          image: yourdockerhub/codelensai-backend:latest
          ports: [{ containerPort: 8080 }]
          envFrom: [{ secretRef: { name: codelensai-secrets } }]
          resources:
            requests: { memory: "512Mi", cpu: "250m" }
            limits: { memory: "1Gi", cpu: "500m" }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 60
            periodSeconds: 30
      terminationGracePeriodSeconds: 60        # drain WebSockets (§15)
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: codelensai-backend-hpa }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: codelensai-backend }
  minReplicas: 2
  maxReplicas: 5
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
```

---

## 15. Deployment Strategies — Rolling / Blue-Green / Canary

| Strategy | How | When to use here |
|---|---|---|
| **Rolling update** (default K8s) | Replace pods N at a time; `maxSurge=1, maxUnavailable=0` | The demo default — zero downtime, simple |
| **Blue-green** | Stand up v2 alongside v1; flip the LB once healthy | Risky changes (DB schema, WS protocol) — instant rollback by flipping back |
| **Canary** | Route 5% → 25% → 100% to v2, watch error rate / review quality | Prompt or model changes — canary on *quality*, not just HTTP errors |

> **Interview gold:** *"For a model or prompt change I'd canary on review quality, not just HTTP 5xx. Route 10% of PRs to the new prompt, compare bug-catch rate and false-positive rate against control via my eval signal, promote only if quality holds. Canarying an AI change on latency alone would miss a prompt that got faster but dumber."*

### Zero-downtime WebSocket draining
`preStop` hook + `terminationGracePeriodSeconds: 60` so a draining pod stops accepting new WS connections but lets in-flight reviews finish. Clients auto-reconnect and resume via missed-message replay (§9.5).

### Database migrations — expand-contract
Flyway/Liquibase with expand-contract: (1) expand (add nullable column/table), (2) deploy code writing both old+new, (3) backfill, (4) contract (drop old later). Keeps rolling updates safe with schema changes.

---

## 16. CI/CD Pipeline — GitHub Actions

```yaml
# .github/workflows/deploy.yml
name: Build & Deploy
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }
env:
  BACKEND_IMAGE: ${{ secrets.DOCKER_USERNAME }}/codelensai-backend
  FRONTEND_IMAGE: ${{ secrets.DOCKER_USERNAME }}/codelensai-frontend
jobs:
  test-backend:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env: { POSTGRES_DB: codelensai_test, POSTGRES_USER: postgres, POSTGRES_PASSWORD: postgres }
        ports: [5432:5432]
        options: --health-cmd pg_isready --health-interval 10s
      redis:
        image: redis:7-alpine
        ports: [6379:6379]
        options: --health-cmd "redis-cli ping" --health-interval 10s
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: cd backend && ./gradlew test

  test-frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: cd frontend && npm ci && npm run lint && npm run test

  # NEW IN v2 — AI quality gate (§18)
  review-quality-eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - run: cd eval && pip install -r requirements.txt && python run_eval.py --ci
        env: { LLM_API_KEY: ${{ secrets.LLM_API_KEY }} }
        # FAILS the build if bug-catch rate drops >5% or false-positive rate rises >5%

  build-and-push:
    needs: [test-backend, test-frontend, review-quality-eval]
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with: { username: ${{ secrets.DOCKER_USERNAME }}, password: ${{ secrets.DOCKER_PASSWORD }} }
      - run: |
          docker build -t $BACKEND_IMAGE:${{ github.sha }} -t $BACKEND_IMAGE:latest ./backend
          docker push $BACKEND_IMAGE:${{ github.sha }} && docker push $BACKEND_IMAGE:latest
          docker build -t $FRONTEND_IMAGE:${{ github.sha }} -t $FRONTEND_IMAGE:latest ./frontend
          docker push $FRONTEND_IMAGE:${{ github.sha }} && docker push $FRONTEND_IMAGE:latest

  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: appleboy/ssh-action@master
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd /opt/codelensai
            docker compose pull && docker compose up -d --remove-orphans && docker system prune -f
```

**Railway alternative:** swap the `deploy` job for `railwayapp/cli-action@v1` with `command: up`.

---

## 17. Monitoring & Observability

### Prometheus
```yaml
global: { scrape_interval: 15s }
scrape_configs:
  - job_name: 'codelensai-backend'
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ['backend:8080'] }]
```

### Grafana — 4 system panels
1. **Review Latency** — webhook→complete (P50 < 15s, P95 < 30s) — `review_duration_seconds_bucket`
2. **Queue Depth** — pending Redis Stream jobs (alert > 50) — `review_queue_pending_total`
3. **Error Rate** — 5xx + failed reviews (< 1%) — `http_server_requests_seconds_count{status=~"5.."}`
4. **Token Usage** — tokens/review (cost) — `llm_tokens_total`

### The "Scaling Decision" to document (README)
> **Redis Pub/Sub over Kafka for WebSocket fan-out.** When I scaled to 2 replicas, clients on different servers couldn't receive each other's streams. **Kafka:** persistent log + replay, but 3 brokers + KRaft + 4GB RAM. **Redis Pub/Sub:** already running it, sub-ms latency, fire-and-forget. **Decision:** Redis Pub/Sub — at ~100 concurrent connections, replay isn't needed (a disconnected client re-fetches via REST / resumes via §9.5). **I'd switch to Kafka** if we added multiple independent downstream consumers needing replay.

---

## 18. DevOps / MLOps Wrapper

The biggest gap in v1: there was no way to measure if the AI reviews were *good*. For an AI product, that's the whole game.

### 18.1 LLM observability (Langfuse)
Trace every review: prompt version, model, tokens in/out, cost, latency, the diff chunk, the structured output. Panels: cost per review, p50/p95 review latency (target < 30s — your hook), tokens/review, reviews per provider (when fallback fires).

### 18.2 Review-quality eval harness (the differentiator)
The question you *will* be asked: *"How do you know the AI reviews are any good?"* Answer with a benchmark.

- ~30 diffs with **planted bugs** (null deref, SQL injection, race condition, off-by-one, missing error handling, hardcoded secret).
- Record expected `(file, line, severity, bug_class)`.
- Compute **bug-catch rate (recall)**, **false-positive rate (precision)**, **severity accuracy**.
- Runs in CI; **fails the build** if catch rate drops >5%.

> **Interview gold:** *"I can tell you my AI catches ~85% of planted bugs at a ~12% false-positive rate. I don't guess whether it works — I measure it, and the eval gates CI."*

(Starter code provided separately in `codelens_starter_code.md`.)

### 18.3 Prompt versioning
Prompts as versioned files (`prompts/review_v4.txt`), never inline. Each review logs its prompt version. A new version must beat the old on the eval before shipping.

### 18.4 Cost guardrails
Per-review token cap; per-user/repo daily budget; public-demo global daily budget so a traffic spike doesn't drain credits. Show cost per review in the UI.

### 18.5 Feedback flywheel
Thumbs-up/down per comment. Dismissed comments are the highest-signal data: a stream of false positives on a comment type → tune the prompt or add a negative eval case.

### 18.6 Model/prompt regression gate in CI
```
review-quality-eval  → FAIL if bug-catch rate drops >5% or FP rate rises >5%
cost-regression      → FAIL if mean tokens/review rises >20%
```

---

## 19. Differentiator Features for MAANG/FAANG

Build 1–2; write the rest into `docs/future-work/`.

### 🟢 G1 — Repo-Aware Context Retrieval (headline differentiator — BUILD THIS)
Reviewing a diff *in isolation* misses cross-file bugs. Before sending the diff to the LLM, retrieve related code via embeddings: the full body of changed functions, their **callers**, the test file, referenced types. Embed the repo into pgvector (add the `vector` extension to your existing Postgres); retrieve top-k related chunks per PR.

> *"I don't just send the diff — I retrieve the changed function's callers and tests, so the reviewer catches 'you changed this signature but didn't update the two callers' — a class of bug diff-only tools structurally cannot see. Same RAG insight as schema-aware text-to-SQL: retrieve the relevant context, don't dump everything."*

### 🟢 G2 — Review-Quality Eval Harness with Planted Bugs (BUILD THIS)
Covered in §18.2. Both an MLOps practice and a differentiator — almost no fresher measures AI quality.

### 🟢 G3 — PR Risk Score + Review Summary (BUILD THIS — cheap, high-impact)
```
PR Risk: HIGH 🔴
  • Touches authentication code
  • No tests added (+240 lines, 0 test files)
  • 1 CRITICAL, 3 WARNING comments
  • Modifies a function with 6 callers
Recommended: request changes before merge.
```
Heuristics (sensitive paths? tests added? diff size?) + an LLM summary.

### 🟡 G4 — AST-Aware Diff Chunking (tree-sitter)
Chunk by function/class boundaries via tree-sitter so a function is never split across LLM calls. Multi-language via grammars.

### 🟡 G5 — Real-Time Collaborative Presence (deliver on "collaborative")
Live avatars of who's viewing the PR, live cursors, "Alice is typing…" over the same WebSocket bus. Makes the demo *feel* like a product.

### 🟠 G6 — "Apply Suggestion" → GitHub commit (write up / optional)
Each `code_suggestion` gets an "Apply" button that opens a suggestion/commit on the PR via the GitHub API.

### 🟠 G7 — Learning from Dismissals (write up)
Consistently-dismissed comment types are auto-suppressed and fed to the eval set as negatives.

| If you have… | Build |
|---|---|
| 1 extra week | G2 + G3 |
| 2 extra weeks | + G1 (headline) |
| 3 extra weeks | + G5 (collaborative presence) |
| Writing only | G4, G6, G7 as `docs/future-work/` + ADRs |

---

## 20. README Blueprint

```markdown
# CodeLens AI — Real-time AI Code Review Platform
> Every PR gets an AI review in under 30 seconds. Watch comments stream in real-time.

[LIVE DEMO](https://codelensai.example.com) | [Video Demo](https://youtube.com/...)
![Architecture](docs/architecture.png)
![PR Review](docs/screenshot-review.png)
![Dashboard](docs/screenshot-dashboard.png)

## Features
- GitHub webhook integration — idempotent, auto-reviews every new PR
- Frontier-LLM reviews with severity (Critical/Warning/Suggestion) + confidence
- Real-time streaming — comments appear word-by-word via WebSocket
- Repo-aware context retrieval — catches cross-file bugs
- Side-by-side diff with inline AI annotations
- Dashboard + review-quality eval (bug-catch rate)

## Tech Stack
Spring Boot 3 (Java 21) · React 18 · TS · PostgreSQL 16 (+pgvector) · Redis 7 ·
WebSocket (STOMP/SockJS) · LLM adapter (GPT-5-class) · Langfuse · Docker · GitHub Actions

## Key Architecture Decisions
1. PostgreSQL over MongoDB — relational data model
2. Redis Streams over Kafka — operational simplicity at our throughput
3. Redis Pub/Sub for WebSocket scaling — horizontal backend scaling
4. Idempotent webhooks (delivery-ID dedup) — GitHub delivers duplicates
5. Model-agnostic adapter — swap LLM via one env var
6. Eval-gated CI — merges blocked on review-quality regression

## Quick Start
    git clone https://github.com/you/codelensai.git && cd codelensai
    cp .env.example .env   # add GitHub + LLM keys
    docker compose up -d
    # Frontend :3000 · Backend :8080 · Grafana :3001 · Langfuse :3002

## Monitoring
Prometheus + Grafana (latency, queue depth, errors, tokens) + Langfuse (cost/quality)

## License
MIT
```

---

## 21. Interview Prep — Questions & Answers

### System Design
**Q1: Walk me through what happens when a developer pushes code.** Developer pushes → GitHub fires a webhook → Spring Boot validates HMAC, **dedups on the delivery UUID**, enqueues a job in Redis Streams. A worker fetches the diff, chunks it, calls the LLM (via adapter) in streaming mode. Tokens flow LLM→backend via SSE, then backend→browsers via WebSocket (cross-pod via Redis Pub/Sub). On completion, comments persist to Postgres (idempotent on `(pr_id, head_sha)`) and a summary posts to the PR.

**Q2: Why PostgreSQL not MongoDB?** Relational model (users→repos→PRs→sessions→comments); ACID for atomic completion; JSONB for AI metadata; tsvector search — without extra services. Mongo would force denormalization or `$lookup`.

**Q3: Is your system CP or AP?** Both — two planes. Live stream is AP (availability over consistency); persisted state is CP (one ACID transaction). Recognizing one product has two regimes is CAP in practice.

**Q4: GitHub sends the same webhook twice — what happens?** Nothing bad. Dedup on `X-GitHub-Delivery` via Redis SETNX (24h TTL); reviews keyed on `(pr_id, head_sha)` so reprocessing upserts. At-least-once delivery + idempotent consumer = effectively-once.

**Q5: Scale to 10×?** Horizontal backend (WS via Redis Pub/Sub); more Stream consumers; Postgres read replicas; cheaper triage model escalating to frontier; migrate to Kafka only if multiple independent consumers need replay.

**Q6: Why Redis Streams over Kafka?** ~100 PRs/hour doesn't justify 3 brokers + KRaft + 4GB RAM. Streams give consumer groups, acks, pending visibility in the Redis we already run. Scaling decision, not day-one.

**Q7: LLM failures/timeouts?** Circuit breaker (Resilience4j) → fallback provider; retry with backoff+jitter; failed jobs to DLQ; PR marked `REVIEW_FAILED` with retry.

**Q8: Explain your WebSocket architecture.** STOMP over SockJS; topic per PR; SockJS fallback behind proxies; Redis Pub/Sub so a token on Server A reaches a client on Server B.

### AI / MLOps
**Q9: How do you know the reviews are good?** ~30 planted-bug benchmark; measure bug-catch rate (~85%), false-positive rate, severity accuracy; eval gates CI (fail if catch rate drops >5%).

**Q10: Why did you move off GPT-4?** Current frontier models reason better across files, emit reliable structured output, handle bigger diffs. But the real answer: model behind an adapter, swappable via one env var.

**Q11: Deploy a prompt change safely?** Canary on review *quality* — 10% of PRs to the new prompt, compare catch/FP rates vs control, promote only if quality holds. Plus expand-contract DB migrations.

**Q12: Biggest differentiator?** Repo-aware context retrieval — embedding the repo and retrieving callers + tests so the reviewer catches cross-file bugs. Same RAG insight as schema-aware text-to-SQL.

### Backend / Frontend / DevOps
**Q13: Diff chunking?** Split by file, then by function/class boundaries (tree-sitter as a stretch); include surrounding context; sequential per PR, parallelizable with virtual threads.

**Q14: Webhook security?** `X-Hub-Signature-256` HMAC-SHA256 with constant-time comparison; reject mismatches with 401.

**Q15: Streaming text in React?** Accumulate tokens in state; blinking cursor on the last char; `useRef` for the STOMP client; debounce to batch rapid tokens.

**Q16: Already-reviewed PR?** Fetch persisted comments via REST; if a review is in progress, combine REST (already streamed) + WebSocket (remaining) using sequence numbers.

**Q17: Multi-stage Docker?** Build stage (JDK, 800MB+) compiles; runtime stage (JRE) copies only the JAR → ~180MB. Smaller attack surface, faster deploys.

**Q18: A scaling decision you made?** Redis Pub/Sub over Kafka for WS fan-out (see §17).

**Q19: What would you monitor?** Latency (P50/P95), queue depth, error rate, token usage/cost; alert on queue > 50, errors > 1%, P95 > 60s. Plus Langfuse for per-review cost/quality.

**Q20: Hardest part?** Not the LLM call — anyone streams tokens. The hard parts: idempotent webhook/job processing, resumable WS streaming across scaled pods via Redis Pub/Sub, and proving review quality with an eval harness.

---

## 22. Deployment Checklist — Go Live

- [ ] Runs end-to-end with `docker compose up`
- [ ] CI/CD passes incl. **review-quality eval gate** (green badge)
- [ ] Deployed publicly (EC2/Railway) with HTTPS
- [ ] GitHub OAuth works on deployed URL
- [ ] Webhook receives real PR events — **deduped** (test by re-delivering)
- [ ] AI review completes in < 30s
- [ ] WebSocket streaming works on deployed version; **survives reconnect** (§9.5)
- [ ] Grafana + Langfuse dashboards accessible (screenshots for README)
- [ ] README: architecture diagram, screenshots, setup, live link, scaling decision
- [ ] Graceful degradation works (kill the LLM provider → `REVIEW_UNAVAILABLE`)
- [ ] `docs/`: SCALING.md, ADRs (incl. idempotency + model-adapter), eval results
- [ ] 2-minute demo video (include the streaming + a deduped re-delivery)

### Cost Estimate (Monthly, live demo)
| Service | Provider | Cost |
|---|---|---|
| EC2 t3.medium (2 vCPU, 4GB) | AWS | ~$30/mo |
| OR Railway.app | Railway | ~$5–20/mo |
| LLM API | provider | ~$5–10/mo (demo) |
| Domain + SSL | Cloudflare | Free |
| **Total** | | **$10–40/month** |

---

## 23. Final Word

This project hits every checkbox Bangalore product companies care about:
- **Real-time systems** (WebSocket + streaming)
- **Third-party API integration** (GitHub + LLM)
- **Event-driven architecture** (webhooks → queue → process → push)
- **Availability & consistency reasoning** (two-plane CAP, idempotency, effectively-once)
- **Pragmatic scaling decisions** (Redis over Kafka, with reasoning)
- **DevOps + MLOps maturity** (Docker, CI/CD, monitoring, eval-gated quality)
- **AI integration done right** (model-agnostic adapter, measured quality, repo-aware context)

The model is the commodity. Your edge is everything around it: idempotent ingestion, resumable streaming across scaled pods, and a measured eval harness. Build it, deploy it, put the live link on your resume — and have an answer for every layer, because you built every layer yourself.

---

*Merged master document (v2) — CodeLens AI Real-time Collaborative Code Review Tool (2026)*
