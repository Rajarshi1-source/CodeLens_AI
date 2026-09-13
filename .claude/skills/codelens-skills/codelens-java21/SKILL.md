---
name: codelens-java21
description: Java 21 LTS specialist for the CodeLens AI backend. Use this skill whenever working on any .java file, build file (pom.xml), or any backend logic in the CodeLens AI code-review platform. Covers virtual threads for concurrent LLM/diff-chunk processing, structured concurrency, record patterns and sealed interfaces for the LLM provider adapter, pattern matching for severity handling, and modern Java idioms. Trigger this even when the user only says "the backend," "the consumer," "the review service," or names a Java class — anything touching the JVM side of CodeLens AI runs through Java 21 conventions and should use this skill.
---

# CodeLens AI — Java 21 LTS

You are a senior Java architect working on **CodeLens AI**, a real-time collaborative code-review platform. The backend is **Spring Boot 4.1.x (Java 21)**, built with Maven. This skill governs how Java is written here: idioms, concurrency, and type design. Spring-specific wiring (controllers, security, WebSocket, Redis) lives in the `codelens-spring-boot` skill — use both together for backend work.

## Reference files (load on demand)

This body is the working summary. Read the matching reference when you need the full treatment:

| Reference | Load when |
|---|---|
| `references/concurrency.md` | Implementing/debugging the diff-chunk fan-out, structured concurrency, thread pinning, backpressure |
| `references/domain-types.md` | Defining or changing any DTO, the sealed provider hierarchy, or entity→DTO mapping (full catalog) |

## What CodeLens needs from Java 21

The backend ingests GitHub webhooks, chunks diffs, calls an LLM per chunk **concurrently**, and streams structured comments back. The Java-language choices that matter most:

1. **Virtual threads** — each PR may fan out into many concurrent LLM calls (one per diff chunk). Virtual threads make this cheap and readable instead of juggling thread pools.
2. **Records** — every DTO (`ReviewToken`, `ReviewComment`, `DiffChunk`, `ReviewPromptContext`) is an immutable record. Never a mutable POJO.
3. **Sealed interfaces** — the LLM provider adapter (`LlmReviewProvider`) and its implementations are modeled as a sealed hierarchy so the compiler enforces exhaustive handling.
4. **Pattern matching** — severity routing and provider dispatch use switch pattern matching, not if/else chains.

## Core conventions (apply on every file)

- **Java 21**, **no preview features** in this project — the production build never uses `--enable-preview`. That rules out `StructuredTaskScope`; use `Executors.newVirtualThreadPerTaskExecutor()` with explicit cancellation for "fail together" cases.
- **Constructor injection only** — no field `@Autowired`. Records and `final` fields everywhere possible.
- **No Lombok in new code** unless the existing file already uses it; prefer records and explicit constructors. (The starter snippets use Lombok `@RequiredArgsConstructor`; match the file you're editing.)
- **Immutability by default** — records for data, `List.copyOf`/`Map.copyOf` for defensive copies.
- **`var`** for local variables where the type is obvious from the right-hand side.
- **Text blocks** for prompts, SQL, and JSON literals.

## Virtual threads for concurrent diff review

This is the headline use in CodeLens. When reviewing a PR, chunks are independent and I/O-bound (LLM calls), so each runs on its own virtual thread:

```java
List<DiffChunk> chunks = diffChunker.chunk(diff, MAX_TOKENS_PER_CHUNK);

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<ChunkReview>> futures = chunks.stream()
        .map(chunk -> executor.submit(() -> aiReviewService.review(chunk)))
        .toList();

    for (var future : futures) {
        ChunkReview review = future.get();   // joins; one slow chunk doesn't block others' progress
        webSocketNotifier.publish(prId, review);
    }
} // executor.close() waits for all tasks
```

Enable virtual threads for Spring's request handling too: `spring.threads.virtual.enabled=true`.

### Concurrent context gathering (fetch diff + callers + tests, fail together)

For the repo-aware retrieval differentiator, gather the diff, the callers, and the test files in parallel — but if any one fails, the whole context is incomplete, so cancel the rest and fail the unit. Do this with the same virtual-thread executor and explicit cancellation — **no preview APIs**. Full helper in `references/concurrency.md`.

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> diff       = executor.submit(() -> gitHubService.fetchDiff(prId));
    Future<List<String>> callers = executor.submit(() -> contextRetriever.callersOf(prId));
    Future<List<String>> tests   = executor.submit(() -> contextRetriever.testsFor(prId));
    try {
        return new ReviewPromptContext(diff.get(), callers.get(), tests.get());
    } catch (ExecutionException e) {
        diff.cancel(true); callers.cancel(true); tests.cancel(true);   // fail together
        throw new ContextGatheringException(e.getCause());
    }
}
```

This is the opposite policy from the per-chunk review loop, where one bad chunk must NOT kill the others — there you collect per-future results and keep going.

## Records for all DTOs

Every DTO is an immutable record with invariants validated in the compact constructor. The full catalog (`ReviewStreamToken`, `ReviewComment`, `DiffChunk`, `ReviewPromptContext`, entity→DTO mappings) is in `references/domain-types.md`. Pattern:

```java
public record ReviewComment(String filePath, int lineNumber, Severity severity,
                            String commentText, String codeSuggestion, double confidence) {
    public ReviewComment {
        if (confidence < 0 || confidence > 1)
            throw new IllegalArgumentException("confidence must be in [0,1]");
    }
}
```

Add static factories (`from(...)`) for mapping entities → DTOs, and never expose entities (which carry `access_token`) across the API boundary.

## Sealed interface for the LLM adapter

The model must never be hardcoded. Model the provider as a sealed hierarchy so adding a provider forces exhaustive handling everywhere (including the fallback switch). Full hierarchy and dispatch examples in `references/domain-types.md`.

```java
public sealed interface LlmReviewProvider
        permits OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider {
    Flux<ReviewToken> streamReview(ReviewPromptContext ctx);   // structured tokens per chunk
    String providerId();
}

public enum Severity { CRITICAL, WARNING, SUGGESTION }
```

## Pattern matching for dispatch and severity

Replace if/else and `instanceof` casts with switch pattern matching:

```java
String label(ReviewToken t) {
    return switch (t.severity()) {
        case CRITICAL   -> "🔴 " + t.text();
        case WARNING    -> "🟡 " + t.text();
        case SUGGESTION -> "🟢 " + t.text();
    };  // exhaustive over the enum — no default needed
}
```

```java
int priorityScore(Object event) {
    return switch (event) {
        case PullRequestEvent pr when pr.action().equals("opened") -> 10;
        case PullRequestEvent pr -> 5;
        case PushEvent ignored -> 1;
        case null -> 0;
        default -> -1;
    };
}
```

## Testing (JUnit 6 + Mockito + Testcontainers 2)

- Unit tests: `@ExtendWith(MockitoExtension.class)`, mock repositories and the `LlmReviewProvider`.
- Integration tests that touch Postgres/Redis: **TestContainers**, never an embedded fake — CodeLens relies on Postgres-specific features (JSONB, tsvector, `UNIQUE(pr_id, head_sha)`). Spin up `postgres:16-alpine` and wire it via `@DynamicPropertySource`; a worthwhile test asserts the unique constraint rejects a duplicate `(pr_id, head_sha)` with `DataIntegrityViolationException` (the idempotency backstop).

## MUST DO
- Use virtual threads for concurrent LLM/diff-chunk calls; size nothing manually.
- Model every DTO as a record; validate invariants in compact constructors.
- Keep the LLM provider behind the sealed `LlmReviewProvider` interface — never call a vendor SDK directly from a service.
- Use exhaustive switch pattern matching over `Severity` and event types.
- Use TestContainers for any test touching Postgres or Redis.

## MUST NOT DO
- Hardcode a model vendor anywhere outside a provider implementation.
- Create fixed-size thread pools for LLM fan-out (defeats the point of virtual threads).
- Use `StructuredTaskScope` or any other preview API — this project builds without `--enable-preview`. For "fail together," use the virtual-thread executor with explicit `cancel(true)`.
- Mutate DTOs or pass mutable collections across layers.

## Build configuration

Maven (Spring Boot parent) targeting Java 21. Use the latest **Spring Boot 4.1.x** patch — Spring Framework 7.0 / Spring Security 7.1, Jackson 3, JUnit 6, Testcontainers 2. Java 21 stays the target (4.1.x is tested on 17–26); the 3.5 line went open-source EOL at 3.5.16 on 2026-06-30:

```xml
<!-- pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>   <!-- use the latest 4.1.x patch available -->
    <relativePath/>
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>   <!-- Boot 4 name for starter-web -->
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webclient</artifactId>   <!-- WebClient + Flux, without the reactive server stack -->
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot4</artifactId>
        <version>2.4.0</version>   <!-- not managed by the Boot BOM; pin explicitly -->
    </dependency>
    <dependency>
        <!-- REQUIRED for the resilience4j annotations on Boot 4: starter-aop was removed
             and resilience4j-spring-boot4 does not pull AOP in transitively. Without this
             @CircuitBreaker/@Retry/@Bulkhead are silently never woven. -->
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aspectj</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-postgresql</artifactId>
        <scope>test</scope>   <!-- Testcontainers 2 renamed every artifactId to testcontainers-*;
                                   version managed by the Spring Boot parent BOM -->
    </dependency>
</dependencies>
```

Build and run with the generated wrapper: `./mvnw clean package` and `./mvnw spring-boot:run` (use `mvnw.cmd` on Windows). The full Initializr dependency set (Security, OAuth2, Validation, Actuator, PostgreSQL driver, Prometheus, Lombok) is selected at project generation; this snippet shows the language/runtime-critical ones.

## Troubleshooting
- **Blocking call pins a carrier thread**: ensure JDBC/HTTP clients used inside virtual threads don't hold `synchronized` monitors across I/O; prefer `ReentrantLock`. Run load tests with `-Djdk.tracePinnedThreads=short` to surface pinning.
- **"Fail together" context gather leaks a running task**: make sure every branch cancels the other futures (`cancel(true)`) before throwing.
- **`Flux` from the provider never completes**: confirm the adapter closes the SSE stream and emits `onComplete` so the WebSocket relay can mark the comment done.
