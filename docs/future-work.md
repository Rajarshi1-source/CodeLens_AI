# Future work

## Shipped (differentiators)

Everything the MVP deferred has landed; the decisions are recorded in ADRs 0005–0009.

| Item | Where |
|---|---|
| **G1 repo-aware context** — pgvector index of the default branch (`vector(512)`, HNSW), callers → tests → neighbours, offline hashing or OpenAI embeddings | [ADR 0005](adr/0005-repo-aware-retrieval-pgvector.md) |
| **G3 risk score + summary** — deterministic, explainable HIGH/MEDIUM/LOW with reasons; LLM summary with a template fallback; risk filter, dashboard chart, GitHub summary | [ADR 0008](adr/0008-risk-score-and-feedback-flywheel.md) |
| **G4 structure-aware chunking** — JavaParser + brace/indent parsers; a function is never split across LLM calls | [ADR 0005](adr/0005-repo-aware-retrieval-pgvector.md) |
| **G5 live presence** — who is viewing a PR and which line each person points at | [ADR 0006](adr/0006-demo-profile-and-websocket-auth.md) |
| **G6 apply suggestion** — idempotent GitHub suggested change; refuses stale heads | [ADR 0008](adr/0008-risk-score-and-feedback-flywheel.md) |
| **G7 feedback flywheel** — votes per finding that survive re-reviews, per-repo rule suppression, negative-case export | [ADR 0008](adr/0008-risk-score-and-feedback-flywheel.md) |
| **Real Claude provider** — official Anthropic Java SDK, structured output, refusal handling | [ADR 0009](adr/0009-llm-event-model-claude-and-cost-controls.md) |
| **Fallback provider** — cross-vendor failover when the primary fails or its breaker opens | [ADR 0009](adr/0009-llm-event-model-claude-and-cost-controls.md) |
| **Daily token budget + cost per review** — atomic Redis budget, USD per review, budget/spend in the UI | [ADR 0009](adr/0009-llm-event-model-claude-and-cost-controls.md) |
| **Resumable stream** — Redis replay buffer, session-aware client state | [ADR 0006](adr/0006-demo-profile-and-websocket-auth.md) |
| **Inline diff anchoring + diff snapshots** | [ADR 0007](adr/0007-diff-snapshots-and-inline-anchoring.md) |
| **Eval growth + cost-regression gate** — 31 cases (6 cross-file), retrieval ablation, risk accuracy, +20% token gate | `eval/`, README |
| **Demo profile + Playwright E2E in CI** | [ADR 0006](adr/0006-demo-profile-and-websocket-auth.md), `frontend/e2e/` |

## Next

- **Stateless JWT / API tokens** for non-browser clients (CLI, IDE plugin). Sessions remain right for the
  SPA ([ADR 0004](adr/0004-oauth2-session-vs-jwt.md)).
- **Tree-sitter parsers** for exact ranges in every language (the brace/indent heuristics are good, not
  perfect: macros, decorators, nested template literals spanning lines).
- **Kafka** for the review/index pipelines once there are several independent consumers or 10K+ PRs/hour
  ([SCALING.md](SCALING.md)).
- **Canary on quality** — route a slice of reviews to a candidate prompt or model and promote it only if
  live feedback (dismiss rate) and the eval agree.
- **Per-organization budgets and pricing** instead of one global daily budget.
- **Collaborator sync** — mirror GitHub repository collaborators into `repository_members` instead of
  owner + explicit members.
- **Learned risk weights** — fit the G3 weights against outcomes (reverts, incident-linked PRs) once there
  is enough history; keep the explanation per factor.
- **Prompt tuning from negatives** — use the curated `must_not_flag` set to tune prompts per rule, not
  just suppress.
