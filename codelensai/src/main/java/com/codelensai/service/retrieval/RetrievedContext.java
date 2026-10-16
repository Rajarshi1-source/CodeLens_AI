package com.codelensai.service.retrieval;

import java.util.List;

/** G1 context for one chunk: formatted caller / test / related snippets and their token cost. */
public record RetrievedContext(List<String> callers, List<String> tests, List<String> related,
                               int estimatedTokens, long latencyMs) {

    public static final RetrievedContext EMPTY = new RetrievedContext(List.of(), List.of(), List.of(), 0, 0);

    public RetrievedContext {
        callers = callers == null ? List.of() : List.copyOf(callers);
        tests = tests == null ? List.of() : List.copyOf(tests);
        related = related == null ? List.of() : List.copyOf(related);
    }

    public int snippetCount() {
        return callers.size() + tests.size() + related.size();
    }
}
