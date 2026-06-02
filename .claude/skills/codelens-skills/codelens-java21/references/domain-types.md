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
/** One streamed fragment pushed over WebSocket as the LLM emits tokens. */
public record ReviewStreamToken(
    long seq,            // per-session sequence number → resumable stream
    String file,
    int line,
    Severity severity,
    String text          // partial fragment to append client-side
) {
    public static ReviewStreamToken of(long seq, String file, int line, Severity sev, String text) {
        return new ReviewStreamToken(seq, file, line, sev, text);
    }
}

/** A completed, schema-validated comment from the provider. */
public record ReviewComment(
    String filePath,
    int lineNumber,
    Severity severity,
    String commentText,
    String codeSuggestion,   // nullable
    double confidence
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
    String language       // detected; drives the language hint in the prompt
) {}

public record ReviewPromptContext(
    DiffChunk chunk,
    List<String> callers,     // G1: functions that call the changed code
    List<String> tests,       // G1: associated test files
    String systemContext
) {
    public ReviewPromptContext {
        callers = List.copyOf(callers);   // defensive immutable copies
        tests = List.copyOf(tests);
    }
}
```

## 4. The sealed LlmReviewProvider hierarchy

```java
public sealed interface LlmReviewProvider
        permits OpenAiGpt5Provider, AnthropicClaudeProvider, LocalModelProvider {

    /** Streams structured review tokens for one diff chunk via the provider's SSE. */
    Flux<ReviewToken> streamReview(ReviewPromptContext ctx);

    /** Stable id used in logs, review_sessions.model_used, and provider selection. */
    String providerId();
}
```

```java
public record ReviewToken(String file, int line, Severity severity, String text) {}
```

Each implementation translates the provider's structured-output stream into `Flux<ReviewToken>`. The compiler enforces that any exhaustive `switch` over a `LlmReviewProvider` handles all three — add a fourth provider and every such switch fails to compile until updated, which is exactly the safety net you want for the fallback logic.

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
