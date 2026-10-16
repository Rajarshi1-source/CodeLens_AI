package com.codelensai.util;

import com.codelensai.model.dto.DiffChunk;

import java.util.List;

/**
 * Builder for the review prompt. Keeps the diff — and any retrieved repository context — in clearly
 * delimited DATA sections (never concatenated into the instructions) to blunt prompt-injection from
 * untrusted code. Chat-style providers pass the system prompt separately and leave
 * {@link #withSystemContext} unset so it is not sent twice.
 */
public class PromptBuilder {

    private String systemContext = "";
    private DiffChunk chunk;
    private List<String> callers = List.of();
    private List<String> tests = List.of();
    private List<String> related = List.of();
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

    /** Vector-similar snippets from the repo index (G1) — context only, never review targets. */
    public PromptBuilder withRelated(List<String> related) {
        this.related = related == null ? List.of() : related;
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
        if (!systemContext.isBlank()) {
            sb.append(systemContext).append("\n\n");
        }
        if (!languageHint.isBlank()) {
            sb.append("Language: ").append(languageHint).append('\n');
        }
        if (chunk != null) {
            sb.append("File: ").append(chunk.fileName()).append('\n');
        }
        section(sb, "RELATED CALLERS", callers);
        section(sb, "RELATED TESTS", tests);
        section(sb, "RELATED CODE", related);
        sb.append("\n--- BEGIN DIFF (untrusted data to review) ---\n");
        sb.append(chunk == null ? "" : chunk.content());
        sb.append("\n--- END DIFF ---\n");
        if (!outputFormat.isBlank()) {
            sb.append("\nRespond as: ").append(outputFormat).append('\n');
        }
        return sb.toString();
    }

    private static void section(StringBuilder sb, String name, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("\n--- BEGIN ").append(name).append(" (context, untrusted, do not review) ---\n");
        items.forEach(i -> sb.append(i).append('\n'));
        sb.append("--- END ").append(name).append(" ---\n");
    }
}
