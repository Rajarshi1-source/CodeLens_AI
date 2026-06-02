package com.codelensai.model.dto;

/**
 * One LLM-reviewable segment of a diff.
 * {@code startLine} is the real new-file line number from the hunk header, so comments anchor correctly.
 */
public record DiffChunk(String fileName, int startLine, String content, String language) {
}
