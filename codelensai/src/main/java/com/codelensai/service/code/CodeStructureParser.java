package com.codelensai.service.code;

import java.util.List;

/**
 * Strategy that finds structural units (classes/functions) in a source file — the basis of G4
 * structure-aware chunking ("a function is never split across LLM calls") and of the G1 code index.
 *
 * <p>Implementations are pure Java (JavaParser for Java, brace/indent heuristics elsewhere) so they
 * behave identically on every OS and in CI. A tree-sitter implementation could be dropped in behind this
 * interface later without touching callers.
 */
public interface CodeStructureParser {

    boolean supports(String language);

    /** Units sorted by start line; nested units (methods inside a class) are all returned. */
    List<CodeUnit> parse(String path, String source);
}
