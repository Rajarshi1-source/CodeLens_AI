# ADR 0002 — Model-agnostic LLM adapter

- Status: Accepted
- Date: 2026

## Context

The product's edge is the system around the model, not the model itself. Hardcoding a vendor creates
lock-in, and frontier models change in price and quality frequently.

## Decision

Put every LLM behind a sealed `LlmReviewProvider` interface
(`permits OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider`) that streams
`ReviewToken`s for a diff chunk. The active provider is chosen from config:

```yaml
codelens:
  llm:
    provider: ${LLM_PROVIDER:local}   # local | gpt5 | claude
    review-model: ${REVIEW_MODEL:local-mock}
```

- `local` — deterministic offline heuristics; the MVP/CI default (no API key, reproducible evals).
- `gpt5` — real OpenAI provider (SSE streaming, structured output).
- `claude` — stub (documented next milestone).

## Consequences

- Swap providers with one env var; no recompile.
- Tests and the eval gate run fully offline against the local provider.
- Resilience4j (circuit breaker + retry + timeout + bulkhead) wraps the provider call; a configured
  `fallback-provider` is the documented path for automatic failover.
