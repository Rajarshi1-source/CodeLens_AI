package com.codelensai.model.dto;

import java.util.List;

/**
 * One LLM-reviewable segment of a diff.
 * {@code startLine} is the real new-file line number from the hunk header, so comments anchor correctly.
 * {@code symbols} are the names of the code units the chunk touches (enclosing functions/classes and
 * names declared on added lines) — the keys for G1 caller/test retrieval.
 */
public record DiffChunk(String fileName, int startLine, String content, String language, List<String> symbols) {

    public DiffChunk {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public DiffChunk(String fileName, int startLine, String content, String language) {
        this(fileName, startLine, content, language, List.of());
    }
}
