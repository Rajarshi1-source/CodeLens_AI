package com.codelensai.service.cost;

/**
 * The daily LLM token budget would be exceeded by this call. Excluded from the {@code llm} circuit
 * breaker and retry: retrying cannot help, and it must not trip the breaker for other callers.
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(long requested, long used, long limit) {
        super("daily token budget exhausted (requested=" + requested + ", used=" + used + ", limit=" + limit + ")");
    }
}
