# Future work (post-MVP)

These are intentionally out of MVP scope. They are captured here so the boundary is explicit and the
next milestones are clear.

## G1 — Repo-aware context retrieval (headline differentiator)

Reviewing a diff in isolation misses cross-file bugs. Before sending a chunk to the LLM, retrieve
related code via embeddings: the changed function's body, its callers, the test file, referenced
types. Plan: add the `vector` extension to Postgres (pgvector), embed the repo, retrieve top-k chunks
per PR. The plumbing already exists — `ReviewPromptContext` carries `callers`/`tests` and
`PromptBuilder.withRetrievedContext()` is present but currently fed empty lists.

## G3 — PR risk score + review summary

Cheap, high-impact: combine heuristics (sensitive paths touched? tests added? diff size? number of
callers of a changed function?) with an LLM summary to emit a HIGH/MEDIUM/LOW risk badge and a
recommended action. Needs a risk field on the review session and a small scoring service.

## Real Anthropic Claude provider

`AnthropicClaudeProvider` is a stub that throws. Implement the WebClient SSE call mirroring
`OpenAiGpt5Provider`, then select via `codelens.llm.provider=claude`. This also unlocks
`codelens.llm.fallback-provider` for true cross-vendor failover.

## Wire the existing-but-idle pieces

- **Fallback provider** — `LlmConfig`/`AIReviewService` should fall back to `fallback-provider` when
  the primary's circuit breaker opens.
- **Daily token budget** — `RateLimiterService.consumeDailyTokens()` exists but is not yet invoked in
  the review path; enforce the per-day cap and surface cost in the UI.

## Comment feedback flywheel

The `comment_feedback` table, `CommentFeedback` entity, and repository exist. Add an endpoint to
submit thumbs up/down per comment, then feed consistently-dismissed comment types into the eval set as
negatives and use them to tune prompts.

## Other differentiators (write-ups)

- AST-aware diff chunking (tree-sitter) so a function is never split across LLM calls.
- Real-time collaborative presence (live avatars / cursors over the same WebSocket bus).
- "Apply suggestion" → open a commit/suggestion on the PR via the GitHub API.

## Eval coverage

Grow `eval/testset.jsonl` from the current handful of cases toward ~30 planted-bug diffs, and add a
`cost-regression` CI gate (fail if mean tokens/review rises > 20%).
