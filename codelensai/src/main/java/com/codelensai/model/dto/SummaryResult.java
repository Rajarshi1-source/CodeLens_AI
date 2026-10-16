package com.codelensai.model.dto;

/** A generated PR summary plus the tokens it cost ({@code usage} may be null for the template fallback). */
public record SummaryResult(String text, ReviewEvent.Usage usage) {
}
