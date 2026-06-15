# ADR 0003 — Redis Streams + Pub/Sub over Kafka

- Status: Accepted
- Date: 2026

## Context

We need a job queue (diff-review jobs) and a cross-pod broadcast bus (WebSocket fan-out). Kafka is the
default "serious" choice, but it carries real operational weight.

## Decision

Use **Redis 7** for three roles: cache + idempotency keys, a job queue via **Redis Streams**
(consumer groups, acks, `XPENDING` visibility, `BLOCK` backpressure), and **Pub/Sub** for WebSocket
fan-out across pods.

At ~100 PRs/hour, Kafka's 3 brokers + KRaft + ~4GB RAM are unjustified; Redis does the same work in a
~50MB process we already run for caching.

## Consequences

- One datastore covers cache, queue, and fan-out — minimal ops.
- Streams give effectively-once semantics with an idempotent consumer (see ADR 0001).
- Migrate the queue/bus to Kafka only if we need durable replay for multiple independent consumers or
  scale to 10K+ events/hour (see [../SCALING.md](../SCALING.md)).
