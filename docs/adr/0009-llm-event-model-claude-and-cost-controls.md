# ADR 0009 — LLM event model, the Claude provider, fallback and cost controls

- Status: Accepted — amends [ADR 0002](0002-model-agnostic-adapter.md) (`ReviewToken` → `ReviewEvent`; Claude is real)
- Date: 2026-10

## Context

ADR 0002 put every model behind `LlmReviewProvider` streaming `ReviewToken`s. Running real models showed
what that shape could not express: a retry or a server-side fallback restarting a chunk midway, token
usage for cost accounting, and a summary call. The OpenAI provider also buffered the whole response, so
"streaming" was cosmetic; Claude was a stub; and nothing capped spend.

## Decision

- **Sealed `ReviewEvent`** = `Reset(reason)` | `Finding(file, line, severity, text, codeSuggestion,
  confidence, ruleKey)` | `Usage(provider, model, in, out, estimated)`, consumed with exhaustive switches
  (no `default`). `Reset` clears a chunk's findings (live and persisted) when a retry or fallback restarts it.
- **True incremental streaming**: a shared `StreamingCommentParser` emits each `comments[]` object as soon
  as it closes in the model's JSON output; findings are pushed to STOMP as they arrive.
- **Claude via the official Anthropic Java SDK** (`com.anthropic:anthropic-java`): `claude-opus-5-5`,
  JSON-schema structured output, `effort: high`, no temperature and no thinking config; the SDK's own
  retries are off (`maxRetries(0)`) because Resilience4j owns retry. The blocking SDK stream runs on a
  virtual thread bridged with `Flux.create`, with a single dispose hook that closes the HTTP stream.
  Refusals: server-side refusal fallback (`fallbacks: "default"`, beta header) except on Haiku, a `Reset`
  when the fallback takes over, and `LlmRefusalException` on `stop_reason=refusal`.
- **Resilience and fallback**: `@Retry(fallbackMethod)` + `@CircuitBreaker` + `@Bulkhead` around the
  provider stream; the fallback streams from `codelens.llm.fallback-provider` (counter `llm.fallback`).
  `BudgetExceededException` and refusals are ignored by retry/breaker (retrying cannot help).
- **Cost controls**: `ModelPricing` (USD per Mtok per model) writes `cost_usd`, input/output tokens and the
  serving model per session; `TokenBudgetService` enforces a daily UTC token budget atomically in Redis
  (Lua reserve → reconcile → release, never overshooting under concurrency; skipped for zero-price
  models). If every chunk is over budget the review is `REVIEW_UNAVAILABLE` (`BUDGET_EXCEEDED`); if some
  are, it completes partially. Metrics: tokens, cost, fallbacks, refusals, shed chunks, budget left.
- **Cost-regression gate** in the eval: mean tokens per review may not grow > 20% over the baseline.
- **Jackson 2 + 3 coexist**: the Anthropic SDK brings Jackson 2 (`com.fasterxml.jackson`) and OkHttp;
  Spring Boot 4 uses Jackson 3 (`tools.jackson`). Different packages, no conflict; our code uses Jackson 3.

## Consequences

- Providers are interchangeable at the event level, and retries/fallbacks are visible and correct in the UI.
- Spend is bounded and observable; a prompt or model change that inflates tokens fails CI.
- The offline `local` provider still emits estimated `Usage`, so cost accounting and the cost gate work
  without any key.
