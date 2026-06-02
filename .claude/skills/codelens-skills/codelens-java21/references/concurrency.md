# CodeLens AI — Java 21 Concurrency Reference

Deep reference for concurrent review execution. Load when implementing or debugging the diff-chunk fan-out, the "fail together" context gather, or thread-pinning problems. The SKILL.md body has the short version; this file is the full treatment. **This project uses no preview APIs** — every pattern here works on a standard Java 21 build with no `--enable-preview`.

## Contents
1. Why virtual threads here
2. The concurrent review executor (full)
3. Chunk-level isolation vs fail-together
4. Concurrent context gathering (fail together, no preview)
5. Avoiding carrier-thread pinning
6. Backpressure and bounded concurrency
7. Spring integration

## 1. Why virtual threads here

Reviewing one PR fans out into N independent LLM calls — one per diff chunk. Each call is I/O-bound (network to the LLM provider) and spends almost all its wall-clock time blocked on a socket. Platform threads would force a pool size tradeoff: too few and chunks queue; too many and you burn memory on mostly-idle stacks. Virtual threads let you spawn one thread per chunk at negligible cost — the JVM unmounts a blocked virtual thread from its carrier, so a thousand in-flight LLM calls cost a thousand cheap continuations, not a thousand OS threads.

## 2. The concurrent review executor (full)

```java
@Service
@RequiredArgsConstructor
public class ConcurrentReviewExecutor {

    private final AIReviewService aiReviewService;
    private final WebSocketNotifier webSocketNotifier;

    /**
     * Reviews all chunks concurrently. Each chunk runs on its own virtual thread.
     * One chunk failing must NOT abort the others (chunk-level isolation), so we
     * collect per-chunk Results rather than throwing on the first failure.
     */
    public List<ChunkResult> reviewAll(long prId, List<DiffChunk> chunks) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChunkResult>> futures = chunks.stream()
                .map(chunk -> executor.submit(() -> reviewOne(prId, chunk)))
                .toList();

            return futures.stream().map(this::join).toList();
        } // close() blocks until every task finishes
    }

    private ChunkResult reviewOne(long prId, DiffChunk chunk) {
        try {
            var comments = aiReviewService.review(chunk);          // streams + collects
            return ChunkResult.ok(chunk, comments);
        } catch (Exception e) {
            // Isolate: this chunk failed, the review as a whole continues.
            webSocketNotifier.sendChunkError(prId, chunk.fileName());
            return ChunkResult.failed(chunk, e.getMessage());
        }
    }

    private ChunkResult join(Future<ChunkResult> f) {
        try { return f.get(); }
        catch (Exception e) { return ChunkResult.failed(null, e.getMessage()); }
    }
}
```

`ChunkResult` is a record with a static `ok`/`failed` factory and an `isFailed()` accessor. After collecting results, the review is `COMPLETED` if at least one chunk succeeded, `REVIEW_FAILED` only if all chunks failed.

## 3. Chunk-level isolation vs fail-together — when to use which

| Situation | Mechanism | Why |
|---|---|---|
| Per-chunk LLM review loop | virtual-thread executor + per-future try/catch | One bad chunk (timeout, malformed output) must not lose the other chunks' comments |
| Fetch diff + callers + tests for ONE prompt | virtual-thread executor + explicit `cancel(true)` on first failure | These are inputs to a single prompt; if any fails the context is incomplete, so cancel the rest and fail the unit |

The mistake to avoid is cancelling siblings in the chunk loop — that throws away good work. Keep the two policies distinct.

## 4. Concurrent context gathering (fail together, no preview)

`StructuredTaskScope` would express this neatly, but it is a **preview API in Java 21** and this project builds without `--enable-preview`. The equivalent with the virtual-thread executor is only a few lines and ships on a standard build:

```java
public ReviewPromptContext gatherContext(long prId) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<String>       diff    = executor.submit(() -> gitHubService.fetchDiff(prId));
        Future<List<String>> callers = executor.submit(() -> contextRetriever.callersOf(prId));
        Future<List<String>> tests   = executor.submit(() -> contextRetriever.testsFor(prId));
        try {
            return new ReviewPromptContext(diff.get(), callers.get(), tests.get());
        } catch (ExecutionException | InterruptedException e) {
            diff.cancel(true); callers.cancel(true); tests.cancel(true);   // fail together
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ContextGatheringException(e.getCause() != null ? e.getCause() : e);
        }
    }
}
```

The three forks are cheap virtual threads; `get()` joins them; the catch cancels any still-running sibling so a failure doesn't leak work. This is the "fail together" policy — distinct from the per-chunk loop's "isolate failures."

## 5. Avoiding carrier-thread pinning

A virtual thread that blocks while holding a `synchronized` monitor *pins* its carrier platform thread, defeating the scalability win. In CodeLens this bites if a provider SDK or an old JDBC driver synchronizes across a network call.

Rules:
- Prefer `ReentrantLock` over `synchronized` for any lock held across I/O.
- Keep HTTP/LLM client calls out of `synchronized` blocks.
- Run with `-Djdk.tracePinnedThreads=short` during load testing to surface pinning.
- HikariCP and the PostgreSQL JDBC driver are fine; verify any third-party LLM SDK doesn't synchronize around its transport.

## 6. Backpressure and bounded concurrency

Unbounded fan-out can overwhelm the LLM provider's rate limit. Bound concurrency with a `Semaphore` acquired inside each virtual thread — the threads are still cheap, but only K calls are in flight at once:

```java
private final Semaphore llmGate = new Semaphore(8);   // tune to provider rate limit

private ChunkResult reviewOne(long prId, DiffChunk chunk) {
    try {
        llmGate.acquire();
        try { return ChunkResult.ok(chunk, aiReviewService.review(chunk)); }
        finally { llmGate.release(); }
    } catch (Exception e) { return ChunkResult.failed(chunk, e.getMessage()); }
}
```

This complements the Redis-based outbound rate limiter (which bounds across pods); the semaphore bounds within a pod.

## 7. Spring integration

- `spring.threads.virtual.enabled=true` makes Tomcat handle each request on a virtual thread.
- Do not also configure a large platform-thread pool — that's redundant and confusing.
- `@Async` methods can run on a virtual-thread executor: define an `AsyncTaskExecutor` bean backed by `Executors.newVirtualThreadPerTaskExecutor()`.
