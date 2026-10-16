# CodeLens AI — Prompt Versioning, Resilience & Real-time Reference

Two cross-cutting backend concerns folded into one reference: (A) the prompt-versioning + eval convention that gates prompt/model changes, and (B) the Resilience4j config plus the Redis Pub/Sub WebSocket fan-out. Load when changing prompts, wiring resilience, or scaling the WebSocket layer.

## Part A — Prompt Versioning Convention

### Why version prompts
The prompt is product code. A prompt edit that "looks fine" can quietly lower bug-catch rate or raise false positives. Versioning + an eval gate makes prompt changes auditable and reversible, and lets every stored review say which prompt produced it.

### Convention
1. **Prompts live as files**, never inline: `prompts/review_v1.txt`, `prompts/review_v2.txt` (current: v2 adds `category`, `suggestion` and the untrusted *retrieved context* sections). The version is in the filename; `REVIEW_PROMPT_VERSION` selects it and v1 is kept for rollback.
2. **Every review logs its prompt version** into `review_sessions.prompt_version`. You can always answer "which prompt produced this comment?"
3. **A new version must beat the current one on the eval** (precision/recall on (file, line), cross-file recall, `must_not_flag`, risk accuracy, mean tokens) before it ships. See `eval/run_eval.py`.
4. **Canary on quality, not latency.** Route ~10% of PRs to the new prompt, compare catch/FP rates against control, promote only if quality holds. A prompt that got faster but dumber must not pass.

### Loading prompts (Spring)
```java
@Component
public class PromptRepository {
    @Value("${codelens.prompts.review-version:v1}") private String reviewVersion;

    private final Map<String,String> cache = new ConcurrentHashMap<>();

    public String reviewPrompt() {
        return cache.computeIfAbsent("review_" + reviewVersion, this::loadFromClasspath);
    }
    public String reviewVersion() { return reviewVersion; }

    private String loadFromClasspath(String name) {
        try (var in = getClass().getResourceAsStream("/prompts/" + name + ".txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("missing prompt " + name, e); }
    }
}
```

### PromptBuilder via ReviewPromptRenderer (shared by every provider)
```java
// ReviewPromptRenderer — Claude, OpenAI and local all render the same prompt, so G1 context is never dropped
String user = new PromptBuilder()
    .withDiffChunk(ctx.chunk())
    .withRetrievedContext(ctx.callers(), ctx.tests(), ctx.related())   // G1: callers, tests, neighbours
    .withLanguageHint(ctx.chunk().language())
    .build();
// the system prompt is promptRepository.reviewPrompt(); the output format is ReviewOutputSchema (strict JSON
// schema: file, line, severity, category, comment, suggestion, confidence), not prose instructions
```

### Treat the diff — and retrieved code — as untrusted data
Diff content and retrieved repository code can contain prompt-injection attempts ("ignore previous instructions..."). Keep both in clearly-delimited data sections of the prompt, never concatenated into the instruction text; `review_v2` states that diff and RELATED sections are data to review, not commands to follow.

### CI regression gates (match §16/§18 of the master plan)
```
review-quality-eval  → FAIL below the floors (precision/recall/cross-file recall/risk accuracy 0.8), on a >5% drop vs
                       baseline, on new must_not_flag hits, or when retrieval stops helping (--ablate-retrieval)
cost-regression      → FAIL if mean tokens/review rises >20% over eval/baseline.json (same run)
```

---

## Part B — Resilience4j + Real-time Fan-out

### Resilience config (application.yml)
```yaml
resilience4j:
  circuitbreaker:
    instances:
      llm:    { failureRateThreshold: 50, slidingWindowSize: 20, waitDurationInOpenState: 30s }
      github: { failureRateThreshold: 50, slidingWindowSize: 20, waitDurationInOpenState: 15s }
  retry:
    instances:
      llm:    { maxAttempts: 3, waitDuration: 1s, enableExponentialBackoff: true, enableRandomizedWait: true }
      github: { maxAttempts: 3, waitDuration: 500ms, enableExponentialBackoff: true }
  timelimiter:
    instances:
      llm:    { timeoutDuration: 60s }
      github: { timeoutDuration: 10s }
  bulkhead:
    instances:
      llm:    { maxConcurrentCalls: 16 }
      github: { maxConcurrentCalls: 8 }
```

### Applying it
```java
// AIReviewService — the provider stream itself is the protected call (Flux-aware Resilience4j operators)
@Retry(name = "llm", fallbackMethod = "fallback")
@CircuitBreaker(name = "llm")
@Bulkhead(name = "llm")
public Flux<ReviewEvent> stream(ReviewPromptContext ctx) {
    return guarded(registry.primary(), ctx, "subscribe");   // rate limit, budget reserve/reconcile, Reset prefix
}

// The budget is exhausted: falling back to another vendor would only spend more — surface it.
private Flux<ReviewEvent> fallback(ReviewPromptContext ctx, BudgetExceededException e) {
    return Flux.error(e);
}

// Anything else (breaker open, retries exhausted, refusal): stream from codelens.llm.fallback-provider.
private Flux<ReviewEvent> fallback(ReviewPromptContext ctx, Throwable t) {
    meters.counter("llm.fallback", "from", primary.providerId(), "to", fb.providerId(), "reason", reason).increment();
    return guarded(fb, ctx, "fallback");                       // emits Reset("fallback") first
}
```

```yaml
resilience4j:
  retry:
    instances:
      llm:
        ignoreExceptions:              # retrying cannot help these
          - com.codelensai.service.cost.BudgetExceededException
          - com.codelensai.service.llm.LlmRefusalException
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException
```

- The `Reset` event at the head of every (re)subscription lets the consumer drop a chunk's partial findings when a retry or the fallback restarts it — live (`reset` STOMP message) and before persisting.
- Annotations only work through the Spring proxy: GitHub calls are annotated on the separate `HttpGitHubApiClient` bean, never on a method `GitHubService` calls on itself.
- Separate bulkheads keep an LLM slowdown from starving GitHub or embedding calls. Virtual threads make the concurrency cheap; the bulkhead caps it.
- Retries use backoff **with jitter**; respect `Retry-After` on 429s. The Anthropic SDK's own retries are off (`maxRetries(0)`).
- A failing chunk fails alone — chunk-level isolation. On total outage with no usable fallback: `REVIEW_UNAVAILABLE` (`LLM_UNAVAILABLE`); all chunks over budget: `REVIEW_UNAVAILABLE` (`BUDGET_EXCEEDED`).

### Outbound rate limiting (Redis sliding window)
Bound calls **across pods** (the in-pod semaphore from codelens-java21 is complementary):
```
rate:llm:global   → sliding-window counter; excess requests queue in the Stream
rate:github:{user} → per-user GitHub token limit
```

### WebSocket fan-out via Redis Pub/Sub (+ replay buffer)
The simple STOMP broker is pod-local. `WebSocketNotifier` publishes every event to a per-PR channel and appends it
to the replay buffer; `ReviewRelay` on every pod forwards to its local sessions:

```java
// Producer (WebSocketNotifier): one envelope per event; payload is the already-serialized ReviewTopicMessage
redis.convertAndSend("review:" + prId, json(new ReviewBusMessage(ReviewBusMessage.REVIEW, payload)));
buffer.append(prId, sessionId, seq, payload);           // Redis Stream review:tokens:{prId}, MAXLEN ~5000, 2h TTL

// Every pod (ReviewRelay, registered on PatternTopic "review:*")
public void onMessage(Message message, byte[] pattern) {
    String prId = channel.substring(channel.indexOf(':') + 1);      // the PR id comes from the CHANNEL
    ReviewBusMessage bus = objectMapper.readValue(message.getBody(), ReviewBusMessage.class);
    switch (bus.topic()) {
        case ReviewBusMessage.REVIEW, ReviewBusMessage.STATUS ->
                stomp.convertAndSend("/topic/pr/" + prId + "/" + bus.topic(), bus.payload());
        default -> log.debug("Unknown review bus topic: {}", bus.topic());
    }
}
```

Messages carry `sessionId` + a per-session `seq`; a reconnecting client asks `@SubscribeMapping("/pr/{id}/replay")`
for everything after its last `(sessionId, seq)` (see `presence-and-resume.md`). WebSocket connections stay sticky
to a pod (scale on connection count); review jobs are stateless in the Stream (scale on queue depth).

### Graceful WebSocket draining
On shutdown, the pod stops accepting new WS connections but lets in-flight reviews finish (`preStop` hook + `terminationGracePeriodSeconds: 60`). Clients auto-reconnect and resume via missed-message replay (resumable stream, see the idempotency reference §8).
