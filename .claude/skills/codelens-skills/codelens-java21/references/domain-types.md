# CodeLens AI — Java 21 Domain Types Reference

Full catalog of records, enums, and the sealed LLM-provider hierarchy. Load when defining or changing any DTO, the provider adapter, or the review wire types. SKILL.md has the conventions; this is the complete set with validation and mapping.

## Contents
1. Enums
2. Wire / streaming records
3. Diff and prompt records
4. The sealed LlmReviewProvider hierarchy
5. Entity → DTO mapping
6. Validation conventions

## 1. Enums

```java
public enum Severity { CRITICAL, WARNING, SUGGESTION }

public enum ReviewStatus {
    PENDING, IN_PROGRESS, COMPLETED, REVIEW_FAILED, REVIEW_UNAVAILABLE
}

public enum PrAction { OPENED, SYNCHRONIZE, REOPENED, OTHER;
    public static PrAction from(String s) {
        return switch (s) {
            case "opened" -> OPENED;
            case "synchronize" -> SYNCHRONIZE;
            case "reopened" -> REOPENED;
            default -> OTHER;
        };
    }
}
```

## 2. Wire / streaming records

```java
/** One finding pushed over WebSocket (type "token" of the sealed ReviewTopicMessage union). */
public record ReviewStreamToken(
    long sessionId,      // review session; a newer session resets the client's stream state
    long seq,            // per-session sequence number → resumable stream / dedup
    int chunk,           // which diff chunk produced it (a "reset" message clears one chunk)
    String key,          // finding fingerprint: sha256(file|line|ruleKey)[:32]
    String file,
    int line,
    Severity severity,
    String text,
    Double confidence,
    String ruleKey
) implements ReviewTopicMessage {}

public sealed interface ReviewTopicMessage
        permits ReviewStreamToken, ReviewTopicMessage.Reset, ReviewTopicMessage.ChunkError {
    record Reset(long sessionId, long seq, int chunk) implements ReviewTopicMessage {}
    record ChunkError(long sessionId, long seq, int chunk, String file) implements ReviewTopicMessage {}
}

public record ReviewStatusMessage(long sessionId, ReviewStatus status, String reason) {}

/** A completed, schema-validated comment from the provider. */
public record ReviewComment(
    String filePath,
    int lineNumber,
    Severity severity,
    String commentText,
    String codeSuggestion,   // nullable
    double confidence,
    String ruleKey,          // e.g. local.sql-concat / llm.general - drives G7 suppression
    String fingerprint       // null -> computed: sha256(filePath|lineNumber|ruleKey)[:32]; stable across re-reviews
) {

    public ReviewComment {
        if (filePath == null || filePath.isBlank())
            throw new IllegalArgumentException("filePath required");
        if (lineNumber < 0)
            throw new IllegalArgumentException("lineNumber must be >= 0");
        if (confidence < 0.0 || confidence > 1.0)
            throw new IllegalArgumentException("confidence must be in [0,1]");
    }
}
```

## 3. Diff and prompt records

```java
public record DiffChunk(
    String fileName,
    int startLine,
    String content,
    String language,      // detected; drives the language hint in the prompt
    List<String> symbols  // G4: enclosing units' names; G1 looks up their callers/tests
) {}

public record ReviewPromptContext(
    DiffChunk chunk,
    List<String> callers,     // G1: functions that call the changed code
    List<String> tests,       // G1: associated test files
    String systemContext,
    List<String> related      // G1: vector-similar code from the repository index
) {
    public ReviewPromptContext {
        callers = callers == null ? List.of() : List.copyOf(callers);   // defensive immutable copies
        tests = tests == null ? List.of() : List.copyOf(tests);
        related = related == null ? List.of() : List.copyOf(related);
    }
}
```

## 4. The sealed LlmReviewProvider hierarchy

```java
public sealed interface LlmReviewProvider
        permits OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider {

    /** Findings as each comment completes, then a final Usage. AIReviewService prefixes the Reset. */
    Flux<ReviewEvent> streamReview(ReviewPromptContext ctx);

    /** PR-level prose summary on the (usually cheaper) summary model. */
    Mono<SummaryResult> summarize(SummaryContext ctx);

    /** Stable id used in logs, metrics, the fallback registry and model_used ("provider:model"). */
    String providerId();

    String reviewModel();
}
```

```java
public sealed interface ReviewEvent {
    record Reset(String reason) implements ReviewEvent {}
    record Finding(String file, int line, Severity severity, String text,
                   String codeSuggestion, double confidence, String ruleKey) implements ReviewEvent {
        public Finding {   // normalize model output instead of throwing
            if (severity == null) severity = Severity.SUGGESTION;
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            if (ruleKey == null || ruleKey.isBlank()) ruleKey = "llm.general";
        }
    }
    record Usage(String providerId, String model, long inputTokens, long outputTokens,
                 boolean estimated) implements ReviewEvent {}
}
```

Each implementation translates the provider's structured-output stream into `Flux<ReviewEvent>`. The compiler enforces that any exhaustive `switch` over a `LlmReviewProvider` handles all three — add a fourth provider and every such switch fails to compile until updated, which is exactly the safety net you want for the fallback logic.

```java
String describeProvider(LlmReviewProvider p) {
    return switch (p) {
        case OpenAiGpt5Provider o    -> "OpenAI " + o.providerId();
        case AnthropicClaudeProvider c -> "Anthropic " + c.providerId();
        case LocalModelProvider l    -> "Local " + l.providerId();
    };  // exhaustive — no default
}
```

## 5. Entity → DTO mapping

Entities are mutable JPA classes; DTOs are records. Map explicitly with static factories — never expose entities (which carry `access_token`, `webhook_secret`) across the API boundary.

```java
public record PullRequestDto(
    long id, int number, String title, String author,
    ReviewStatus status, int filesChanged, int additions, int deletions
) {
    public static PullRequestDto from(PullRequest pr) {
        return new PullRequestDto(
            pr.getId(), pr.getPrNumber(), pr.getTitle(), pr.getAuthor(),
            ReviewStatus.valueOf(pr.getStatus()),
            pr.getFilesChanged(), pr.getAdditions(), pr.getDeletions());
    }
}

public record UserDto(long id, String username, String avatarUrl) {  // NO token field
    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getAvatarUrl());
    }
}
```

## 6. Validation conventions

- Validate invariants in **compact constructors** so an invalid record can never exist.
- Use `List.copyOf` / `Map.copyOf` in compact constructors to make collection fields immutable and null-safe.
- For request DTOs that arrive over HTTP, also annotate with Bean Validation (`@NotBlank`, `@Email`, `@Size`) and `@Valid` at the controller — the record constructor is the last line of defense, the annotations give nice 400 responses.
- Confidence and line numbers are the two fields most likely to carry garbage from a model; validate both and drop comments that fail (see line-number guarding in the Spring skill).
