package com.codelensai.service.llm;

/**
 * The model declined the request ({@code stop_reason=refusal}). Not an outage: it is excluded from the
 * {@code llm} circuit breaker and retry ({@code ignoreExceptions}) and goes straight to the fallback
 * provider instead.
 */
public class LlmRefusalException extends RuntimeException {

    private final String category;

    public LlmRefusalException(String category, String explanation) {
        super("model refused the request" + (category == null ? "" : " (" + category + ")")
                + (explanation == null || explanation.isBlank() ? "" : ": " + explanation));
        this.category = category;
    }

    public String category() {
        return category;
    }
}
