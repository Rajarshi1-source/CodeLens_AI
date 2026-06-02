package com.codelensai.util;

import com.codelensai.model.dto.DiffChunk;

import java.util.List;

/**
 * Builder for the review prompt. Keeps the diff in a clearly delimited DATA section (never
 * concatenated into the instructions) to blunt prompt-injection from untrusted diff content.
 */
public class PromptBuilder {

    private String systemContext = "";
    private DiffChunk chunk;
    private List<String> callers = List.of();
    private List<String> tests = List.of();
    private String outputFormat = "";
    private String languageHint = "";

    public PromptBuilder withSystemContext(String systemContext) {
        this.systemContext = systemContext == null ? "" : systemContext;
        return this;
    }

    public PromptBuilder withDiffChunk(DiffChunk chunk) {
        this.chunk = chunk;
        return this;
    }

    public PromptBuilder withRetrievedContext(List<String> callers, List<String> tests) {
        this.callers = callers == null ? List.of() : callers;
        this.tests = tests == null ? List.of() : tests;
        return this;
    }

    public PromptBuilder withOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat == null ? "" : outputFormat;
        return this;
    }

    public PromptBuilder withLanguageHint(String languageHint) {
        this.languageHint = languageHint == null ? "" : languageHint;
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append(systemContext).append("\n\n");
        if (!languageHint.isBlank()) {
            sb.append("Language: ").append(languageHint).append('\n');
        }
        if (chunk != null) {
            sb.append("File: ").append(chunk.fileName()).append('\n');
        }
        if (!callers.isEmpty()) {
            sb.append("\n--- BEGIN RELATED CALLERS (context) ---\n");
            callers.forEach(c -> sb.append(c).append('\n'));
            sb.append("--- END RELATED CALLERS ---\n");
        }
        if (!tests.isEmpty()) {
            sb.append("\n--- BEGIN RELATED TESTS (context) ---\n");
            tests.forEach(t -> sb.append(t).append('\n'));
            sb.append("--- END RELATED TESTS ---\n");
        }
        sb.append("\n--- BEGIN DIFF (untrusted data to review) ---\n");
        sb.append(chunk == null ? "" : chunk.content());
        sb.append("\n--- END DIFF ---\n");
        if (!outputFormat.isBlank()) {
            sb.append("\nRespond as: ").append(outputFormat).append('\n');
        }
        return sb.toString();
    }
}
