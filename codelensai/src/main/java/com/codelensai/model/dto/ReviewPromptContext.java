package com.codelensai.model.dto;

import java.util.List;

/**
 * Everything a provider needs to review one chunk. {@code callers}, {@code tests} and {@code related}
 * are the G1 repo-aware context retrieved from the code index (callers of changed symbols, their tests,
 * and vector-similar snippets). Collections are defensively copied to stay immutable.
 */
public record ReviewPromptContext(
        DiffChunk chunk,
        List<String> callers,
        List<String> tests,
        String systemContext,
        List<String> related
) {
    public ReviewPromptContext {
        callers = callers == null ? List.of() : List.copyOf(callers);
        tests = tests == null ? List.of() : List.copyOf(tests);
        related = related == null ? List.of() : List.copyOf(related);
    }

    public ReviewPromptContext(DiffChunk chunk, List<String> callers, List<String> tests, String systemContext) {
        this(chunk, callers, tests, systemContext, List.of());
    }

    public static ReviewPromptContext of(DiffChunk chunk, String systemContext) {
        return new ReviewPromptContext(chunk, List.of(), List.of(), systemContext, List.of());
    }

    public boolean hasRetrievedContext() {
        return !callers.isEmpty() || !tests.isEmpty() || !related.isEmpty();
    }
}
