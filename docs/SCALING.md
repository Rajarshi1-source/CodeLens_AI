# Scaling — Redis for WebSocket fan-out, replay and presence (over Kafka)

## The problem

WebSocket connections are sticky to a single backend pod. When the backend scales to 2+ replicas,
a review token produced on pod #1 must still reach a client connected to pod #2. A pod-local STOMP
simple broker cannot do this by itself.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **Redis Pub/Sub** (chosen) | Already running Redis for cache + queue; sub-ms latency; fire-and-forget; trivial ops | No replay/persistence (acceptable — see below) |
| Kafka | Durable log, replay, multiple independent consumers | 3 brokers + KRaft + ~4GB RAM for ~100 events/hour; heavy ops |
| External STOMP broker (RabbitMQ relay) | Mature broker semantics | Another Erlang service for one feature Redis already covers |

## Decision

Use **Redis Pub/Sub**: each pod publishes review events to a channel `review:{prId}`; every pod subscribes
and pushes to its local STOMP sessions. Pub/Sub itself has no replay, so resumability is layered on top:

- **Replay buffer** — every event of the current review session is also appended to a Redis Stream
  `review:tokens:{prId}` (`MAXLEN ~5000`, 2 h TTL, reset when a new session starts). A reconnecting client
  subscribes, *buffers* live events, asks `/app/pr/{id}/replay` for everything after its last
  `(sessionId, seq)`, applies the batch, then the buffered events; duplicates are dropped by sequence
  number. Persisted comments come from REST once the review completes.
- **Presence (G5)** — viewers of a PR live in a Redis hash plus a last-seen sorted set
  (`presence:pr:{id}`), pruned on read by TTL (the apt Redis 7.0 has no per-field `HEXPIRE`). Changes are
  published on `presence-events:{prId}` and every pod relays a snapshot to its local subscribers; STOMP
  disconnects clean up immediately.

Because all of this is per-PR and short-lived, Redis memory stays small and bounded.

## When this would change

Migrate the fan-out to Kafka if we add multiple independent downstream consumers that need durable
replay (e.g. analytics, a separate notification service), or scale to 10K+ PRs/hour. That's a
scaling decision driven by new requirements, not a day-one default.

## Independent scaling units

- **Backend/WS layer** — scale on CPU + active WS connection count (stateful connections).
- **Review workers** — scale on Redis Stream queue depth (stateless jobs); can split into their own deployment.
- **Frontend** — static, CDN/nginx-served; scales independently.
- **Postgres** — vertical + a read replica for dashboard queries.
- **Redis** — single node for MVP; Sentinel/Cluster at scale. Run it with `maxmemory-policy volatile-lru`:
  the `review-jobs` / `index-jobs` streams have no TTL and must never be evicted, while caches, replay
  buffers, presence and budget counters all carry TTLs. (`allkeys-lru` could silently drop queued jobs.)
- **Index workers** — embedding + pgvector writes run on the `index-jobs` stream, separately from reviews;
  they can be scaled or throttled on their own. The HNSW index keeps retrieval in the low milliseconds per
  chunk at per-repository index sizes.
- **LLM spend** — the daily token budget is a single Redis counter updated by a Lua script, so it holds
  across any number of pods without overshooting.
