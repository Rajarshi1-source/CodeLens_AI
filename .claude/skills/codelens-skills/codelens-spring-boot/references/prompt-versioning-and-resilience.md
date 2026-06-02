# CodeLens AI — Prompt Versioning, Resilience & Real-time Reference

Two cross-cutting backend concerns folded into one reference: (A) the prompt-versioning + eval convention that gates prompt/model changes, and (B) the Resilience4j config plus the Redis Pub/Sub WebSocket fan-out. Load when changing prompts, wiring resilience, or scaling the WebSocket layer.

## Part A — Prompt Versioning Convention

### Why version prompts
The prompt is product code. A prompt edit that "looks fine" can quietly lower bug-catch rate or raise false positives. Versioning + an eval gate makes prompt changes auditable and reversible, and lets every stored review say which prompt produced it.

### Convention
1. **Prompts live as files**, never inline: `prompts/review_v4.txt`, `prompts/summary_v2.txt`. The version is in the filename.
2. **Every review logs its prompt version** into `review_sessions.prompt_version`. You can always answer "which prompt produced this comment?"
3. **A new version must beat the current one on the eval** (bug-catch rate, false-positive rate, severity accuracy) before it ships. See the eval harness in `codelens_starter_code.md`.
4. **Canary on quality, not latency.** Route ~10% of PRs to the new prompt, compare catch/FP rates against control, promote only if quality holds. A prompt that got faster but dumber must not pass.

### Loading prompts (Spring)
```java
@Component
public class PromptRepository {
    @Value("${codelens.prompts.review-version:v4}") private String reviewVersion;

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

### PromptBuilder (Builder pattern)
```java
String prompt = new PromptBuilder()
    .withSystemContext(promptRepository.reviewPrompt())
    .withDiffChunk(chunk)
    .withRetrievedContext(callers, tests)        // G1 repo-aware context
    .withOutputFormat("JSON array of {file, line, severity, comment, confidence}")
    .withLanguageHint(chunk.language())
    .build();
```

### Treat the diff as untrusted data
Diff content can contain prompt-injection attempts ("ignore previous instructions..."). Keep the diff in a clearly-delimited data section of the prompt, never concatenated into the instruction text, and state in the system prompt that diff content is data to review, not commands to follow.

### CI regression gates (match §16/§18 of the master plan)
```
review-quality-eval  → FAIL if bug-catch rate drops >5% or FP rate rises >5%
cost-regression      → FAIL if mean tokens/review rises >20%
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
@CircuitBreaker(name = "llm", fallbackMethod = "fallback")
@Retry(name = "llm")
@TimeLimiter(name = "llm")
@Bulkhead(name = "llm")
public Flux<ReviewToken> review(DiffChunk chunk) { /* call provider */ }

private Flux<ReviewToken> fallback(DiffChunk chunk, Throwable t) {
    // Circuit open or exhausted: switch to fallback-provider, or degrade.
    return fallbackProvider.streamReview(/* ctx */);   // see codelens.llm.fallback-provider
}
```

- Separate bulkheads keep an LLM slowdown from starving GitHub calls and vice-versa. Virtual threads (see codelens-java21) make the concurrency cheap; the bulkhead caps it.
- Retries use backoff **with jitter** to avoid a thundering herd against a recovering provider; respect `Retry-After` on 429s.
- Timeout fails the **chunk**, not the whole review — chunk-level isolation.
- On total LLM outage: degrade to `REVIEW_UNAVAILABLE`, auto-retry on recovery. The app still works without AI comments.

### Outbound rate limiting (Redis sliding window)
Bound calls **across pods** (the in-pod semaphore from codelens-java21 is complementary):
```
rate:llm:global   → sliding-window counter; excess requests queue in the Stream
rate:github:{user} → per-user GitHub token limit
```

### WebSocket fan-out via Redis Pub/Sub
The simple STOMP broker is pod-local. To reach a client connected to a different pod, relay through Redis:

```java
// Producer pod: after building a token, publish to a per-PR channel
redis.convertAndSend("review:" + prId, serialize(token));

// Every pod subscribes; on message, push to its local STOMP sessions for that PR
@Bean
RedisMessageListenerContainer reviewRelay(RedisConnectionFactory cf, SimpMessagingTemplate stomp) {
    var container = new RedisMessageListenerContainer();
    container.setConnectionFactory(cf);
    container.addMessageListener((message, pattern) -> {
        ReviewStreamToken token = deserialize(message.getBody());
        stomp.convertAndSend("/topic/pr/" + token.prId() + "/review", token);
    }, new PatternTopic("review:*"));
    return container;
}
```

A token produced on pod #1 thus reaches a client on pod #2. WebSocket connections stay sticky to a pod (scale the WS layer on connection count); review jobs are stateless in the Stream (scale workers on queue depth) — the two layers scale independently.

### Graceful WebSocket draining
On shutdown, the pod stops accepting new WS connections but lets in-flight reviews finish (`preStop` hook + `terminationGracePeriodSeconds: 60`). Clients auto-reconnect and resume via missed-message replay (resumable stream, see the idempotency reference §8).
