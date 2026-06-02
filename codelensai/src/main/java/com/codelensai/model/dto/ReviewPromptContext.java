package com.codelensai.model.dto;

import java.util.List;

/**
 * Everything a provider needs to review one chunk. {@code callers}/{@code tests} are the G1
 * repo-aware context (empty for the MVP). Collections are defensively copied to stay immutable.
 */
public record ReviewPromptContext(
        DiffChunk chunk,
        List<String> callers,
        List<String> tests,
        String systemContext
) {
    public ReviewPromptContext {
        callers = callers == null ? List.of() : List.copyOf(callers);
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    public static ReviewPromptContext of(DiffChunk chunk, String systemContext) {
        return new ReviewPromptContext(chunk, List.of(), List.of(), systemContext);
    }
}
