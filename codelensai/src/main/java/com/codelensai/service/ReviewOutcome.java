package com.codelensai.service;

import com.codelensai.model.dto.ReviewComment;
import com.codelensai.service.risk.RiskAssessment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Everything a completed review persists in one transaction (comments, accounting, risk, metadata). */
public record ReviewOutcome(List<ReviewComment> comments, int elapsedMs, long inputTokens, long outputTokens,
                            BigDecimal costUsd, String summary, RiskAssessment risk, Map<String, Object> metadata) {

    public ReviewOutcome {
        comments = comments == null ? List.of() : List.copyOf(comments);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        metadata = metadata == null ? Map.of() : metadata;
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
