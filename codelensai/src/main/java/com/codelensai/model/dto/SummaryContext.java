package com.codelensai.model.dto;

import com.codelensai.service.risk.RiskAssessment;

import java.util.List;

/** Input for the PR-level review summary (G3). {@code topComments} are already ranked by severity. */
public record SummaryContext(String prTitle, List<String> files, List<ReviewComment> topComments,
                             RiskAssessment risk, String systemContext) {
    public SummaryContext {
        files = files == null ? List.of() : List.copyOf(files);
        topComments = topComments == null ? List.of() : List.copyOf(topComments);
    }
}
