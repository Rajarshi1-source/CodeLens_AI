# CodeLens AI — LLM Providers, Event Model, Budget & Cost

Load when touching `service/llm/*`, `AIReviewService`, `TokenBudgetService`, `ModelPricing`, or provider config.
Decision record: `docs/adr/0009-llm-event-model-claude-and-cost-controls.md`.

## Contents
1. The contract: `LlmReviewProvider` + `ReviewEvent`
2. Incremental structured output: `StreamingCommentParser`
3. Claude (official Anthropic Java SDK)
4. OpenAI GPT-5
5. Local (offline) provider
6. Registry, fallback and the consuming side
7. Token budget (Redis + Lua)
8. Pricing and cost per review
9. Testing providers without the network

## 1. The contract

```java
public sealed interface LlmReviewProvider permits OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider {
    Flux<ReviewEvent> streamReview(ReviewPromptContext ctx);   // Findings as each comment completes, then Usage
    Mono<SummaryResult> summarize(SummaryContext ctx);          // G3 prose summary
    String providerId();                                        // "local" | "gpt5" | "claude"
    String reviewModel();                                       // recorded as provider:model
}

public sealed interface ReviewEvent {
    record Reset(String reason) implements ReviewEvent {}       // discard this chunk's findings so far
    record Finding(String file, int line, Severity severity, String text,
                   String codeSuggestion, double confidence, String ruleKey) implements ReviewEvent {}
    record Usage(String providerId, String model, long inputTokens, long outputTokens,
                 boolean estimated) implements ReviewEvent {}
}

public record ReviewPromptContext(DiffChunk chunk, List<String> callers, List<String> tests,
                                  String systemContext, List<String> related) {}
```

Providers never emit the leading `Reset`; `AIReviewService` prefixes `Reset("subscribe")` to every subscription so
a Resilience4j retry of a partially streamed `Flux` cannot duplicate findings. Consumers switch exhaustively —
no `default` — so adding a variant fails compilation until every consumer handles it.

## 2. Incremental structured output

Every real provider asks for the strict JSON schema in `ReviewOutputSchema`
(`{"comments":[{file, line, severity, category, comment, suggestion, confidence}]}`) and feeds text deltas into
`StreamingCommentParser`, a character-level scanner that tracks string/escape state and brace depth inside the
`comments` array and emits a `Finding` the moment an object closes. Never wait for the whole response and never
regex-parse prose. `parser.reset()` is called when a server-side fallback restarts the answer.

## 3. Claude — `AnthropicClaudeProvider`

- Dependency `com.anthropic:anthropic-java` (pinned). Client: `AnthropicOkHttpClient.builder().apiKey(..)
  .maxRetries(0).timeout(..)` — **SDK retries off**, Resilience4j owns retry and circuit breaking.
- Request (beta Messages API):
  ```java
  MessageCreateParams.builder()
      .model(props.reviewModel())                       // claude-opus-5-5
      .maxTokens(props.maxTokens())                     // 16000: room for thinking + the JSON answer
      .system(ctx.systemContext())
      .addUserMessage(ReviewPromptRenderer.userPrompt(ctx, false))
      .outputConfig(BetaOutputConfig.builder()
          .effort(BetaOutputConfig.Effort.of("high"))   // set explicitly; the default is medium
          .format(jsonSchemaFormat())                   // BetaJsonOutputFormat with ReviewOutputSchema
          .build());
  ```
  **No `temperature` and no `thinking` config** on `claude-opus-5-5` (thinking is always on; both are rejected).
- Streaming: `client.beta().messages().createStreaming(params)` is blocking, so it runs on a virtual thread bridged
  with `Flux.create(sink -> ..., BUFFER)`. Text deltas → parser; thinking deltas are ignored; a
  `BetaMessageAccumulator` gives the final message for `Usage`. Register **one** `sink.onDispose` hook that closes the
  `StreamResponse` and interrupts the worker — `FluxSink` keeps only the first `onDispose` and disposes later ones
  immediately (a second hook closed the stream before `message_stop`).
- Refusals: the server-side refusal fallback is enabled with `addBeta("server-side-fallback-2026-07-01")` +
  `putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))` — **never for Haiku**. When a second answer
  block (or a fallback block) starts, emit `Reset("server-fallback")` and reset the parser. `stop_reason=refusal`
  that is not rescued → `LlmRefusalException(category, explanation)`. `max_tokens` keeps the findings already parsed.
- Summary: `summarize` uses `props.summaryModel()` (default `claude-opus-5-5`; `claude-haiku-5-5` is the cost option)
  with `Effort.LOW`.

## 4. OpenAI — `OpenAiGpt5Provider`

`WebClient` SSE to `/chat/completions` with `stream: true`, `stream_options.include_usage: true`,
`response_format: {type: json_schema, strict: true}` and `max_completion_tokens`. Deltas go through the same parser,
so findings are emitted incrementally; the final chunk's `usage` becomes the `Usage` event.

## 5. Local — `LocalModelProvider`

Deterministic regex rules over added lines (`local.hardcoded-secret`, `local.live-api-key`, `local.sql-concat`,
`local.null-deref`, `local.non-atomic`, `local.off-by-one`, `local.io-unhandled`) with deterministic suggestions for
secrets (`System.getenv`) and SQL (`?` placeholders), plus `LocalContextRules` — cross-file rules that only fire with
retrieved callers (see `retrieval-and-risk.md`). It emits an **estimated** `Usage`, so cost accounting and the eval's
cost gate work offline. `codelens.llm.local.pace-ms` (700 ms under the demo profile) delays its events so live
streaming is visible.

## 6. Registry, fallback, consumer

`LlmProviderRegistry` maps ids to beans (`idOf` is an exhaustive switch over the sealed type) and exposes
`primary()` / `fallback()`. `AIReviewService.stream(ctx)` is `@Retry(name="llm", fallbackMethod="fallback")`
`@CircuitBreaker(name="llm")` `@Bulkhead(name="llm")`; `guarded(provider, ctx, resetReason)` applies the rate limiter,
reserves the budget, prefixes `Reset`, reconciles on `Usage` and releases on error. The fallback method streams from
the fallback provider (counter `llm.fallback{from,to,reason}`), except for `BudgetExceededException`, which is
re-thrown. `summarize` falls back to the deterministic template summary.

`ReviewService` consumes per chunk with a `ChunkCollector`:

```java
switch (event) {
    case ReviewEvent.Reset r   -> { findings.clear(); if (streamed) live.reset(index); }
    case ReviewEvent.Finding f -> { if (findings.putIfAbsent(fingerprint(f), toComment(f)) == null) live.finding(index, c); }
    case ReviewEvent.Usage u   -> usages.add(u);
}
```

## 7. Token budget — `TokenBudgetService`

One Redis counter per UTC day (`rate:llm:tokens:{yyyy-MM-dd}`, 2-day TTL). Reserve = Lua `INCRBY` that rolls itself
back if it would cross the limit (so 200 concurrent reservations never overshoot); reconcile = `INCRBY delta` clamped
at 0; release on failure. Zero-price models skip it (local/demo/eval never blocked). All chunks over budget →
`REVIEW_UNAVAILABLE` + `failure_reason=BUDGET_EXCEEDED`; some → the review completes with `metadata.partial`.
Gauges `llm.budget.remaining` / `llm.budget.limit` (`BudgetMetrics`).

## 8. Pricing and cost

`codelens.llm.pricing.models` (USD per million tokens; bracket keys `"[claude-opus-5-5]"` keep dots and dashes in
YAML map keys). `ModelPricing.cost(model, in, out)` → `review_sessions.cost_usd`, `input_tokens`, `output_tokens`,
`model_used = provider:model`, plus `servedBy` / `fallbackChunks` in metadata; counters `llm.tokens.*` and
`llm.cost.usd{provider,model}`.

## 9. Testing providers

`FakeSseServer` (JDK `HttpServer`) serves canned SSE; point `ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL` at it. Assert:
findings emitted before the stream ends, `Usage` from the final message, refusal → `LlmRefusalException`,
`Reset` on a fallback block, the request body has no `temperature`/`thinking`, and Haiku requests carry no
`fallbacks`. `StreamingCommentParserTest` splits the same JSON at every index.
