package com.codelensai.service.llm;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The strict JSON schema every real provider is asked to emit (OpenAI {@code json_schema} strict mode,
 * Claude {@code output_config.format}). {@code category} becomes the comment's rule key
 * ({@code llm.<category>}), which is what G7 feedback suppression aggregates on.
 */
public final class ReviewOutputSchema {

    public static final List<String> CATEGORIES = List.of(
            "security", "correctness", "concurrency", "performance", "error-handling",
            "maintainability", "testing", "style");

    private static final Set<String> CATEGORY_SET = Set.copyOf(CATEGORIES);

    private ReviewOutputSchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> comment = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("file", "line", "severity", "category", "comment", "suggestion", "confidence"),
                "properties", Map.of(
                        "file", Map.of("type", "string"),
                        "line", Map.of("type", "integer"),
                        "severity", Map.of("type", "string", "enum", List.of("CRITICAL", "WARNING", "SUGGESTION")),
                        "category", Map.of("type", "string", "enum", CATEGORIES),
                        "comment", Map.of("type", "string"),
                        "suggestion", Map.of("type", List.of("string", "null")),
                        "confidence", Map.of("type", "number")));
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("comments"),
                "properties", Map.of("comments", Map.of("type", "array", "items", comment)));
    }

    /** {@code llm.<category>} for a known category, otherwise {@code llm.general}. */
    public static String ruleKeyFor(String category) {
        if (category == null) {
            return "llm.general";
        }
        String c = category.trim().toLowerCase();
        return CATEGORY_SET.contains(c) ? "llm." + c : "llm.general";
    }
}
