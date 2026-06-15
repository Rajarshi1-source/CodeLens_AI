# ADR 0001 — Idempotent ingestion and effectively-once review processing

- Status: Accepted
- Date: 2026

## Context

GitHub delivers webhooks at-least-once (the same event can arrive 2–3 times), and Redis Streams
delivery is also at-least-once (a worker can crash after reading a job but before acking). Without
guards we would run — and pay for — duplicate reviews and emit duplicate comments.

## Decision

Make every stage idempotent on the natural key `(pr_id, head_sha)`, with three layers:

1. **Delivery dedup** — `WebhookController` verifies the HMAC-SHA256 signature first, then dedups on
   `X-GitHub-Delivery` via Redis `SETNX` (24h TTL). Duplicates return `200` as a no-op.
2. **Consumer effect-idempotency** — `ReviewJobConsumer` reads via a consumer group, skips a job if
   a review session already exists for `(pr_id, head_sha)`, and **acks only after success**. A crash
   before ack simply redelivers.
3. **Database backstop** — `UNIQUE(pr_id, head_sha)` on `review_sessions`. A two-worker race loses
   cleanly on `DataIntegrityViolationException` rather than double-writing.

> at-least-once delivery + idempotent consumer = effectively-once processing.

Failed jobs go to a dead-letter stream (`review-jobs-dlq`) after `MAX_RETRIES`; the PR is marked
`REVIEW_FAILED` with a re-review affordance.

## Consequences

- No duplicate reviews or comments under redelivery or at-least-once queue semantics.
- Re-reviewing the same commit upserts rather than appends.
- The persisted-state plane is CP (review completion is one transaction); the live stream is AP.
