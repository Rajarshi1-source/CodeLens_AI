# Scaling — Redis Pub/Sub over Kafka for WebSocket fan-out

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

Use **Redis Pub/Sub**: each pod publishes tokens to a channel `review:{prId}`; every pod subscribes
and pushes to its local STOMP sessions. At ~100 concurrent connections, replay isn't needed — a
disconnected client re-fetches persisted comments via REST and resumes the live stream using
per-session sequence numbers (resumable stream).

## When this would change

Migrate the fan-out to Kafka if we add multiple independent downstream consumers that need durable
replay (e.g. analytics, a separate notification service), or scale to 10K+ PRs/hour. That's a
scaling decision driven by new requirements, not a day-one default.

## Independent scaling units

- **Backend/WS layer** — scale on CPU + active WS connection count (stateful connections).
- **Review workers** — scale on Redis Stream queue depth (stateless jobs); can split into their own deployment.
- **Frontend** — static, CDN/nginx-served; scales independently.
- **Postgres** — vertical + a read replica for dashboard queries.
- **Redis** — single node for MVP; Sentinel/Cluster at scale.
