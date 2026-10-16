package com.codelensai.service.risk;

import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.enums.Severity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Renders the Markdown summary comment posted on the GitHub PR (risk badge, reasons, summary, counts, cost). */
public final class ReviewCommentFormatter {

    private ReviewCommentFormatter() {
    }

    public static String summaryMarkdown(RiskAssessment risk, String summary, List<ReviewComment> comments,
                                         BigDecimal costUsd, String model) {
        StringBuilder sb = new StringBuilder("## CodeLens AI review\n\n");
        if (risk != null) {
            sb.append("**Risk: ").append(risk.level()).append(" (").append(risk.score()).append("/100)**\n\n");
            for (RiskAssessment.Reason r : risk.reasons()) {
                sb.append("- ").append(r.detail()).append('\n');
            }
            sb.append("\n**Recommended:** ").append(risk.recommendedAction()).append("\n\n");
        }
        if (summary != null && !summary.isBlank()) {
            sb.append(summary.trim()).append("\n\n");
        }
        long critical = count(comments, Severity.CRITICAL);
        long warning = count(comments, Severity.WARNING);
        long suggestion = count(comments, Severity.SUGGESTION);
        sb.append("| Critical | Warning | Suggestion |\n|---:|---:|---:|\n")
                .append("| ").append(critical).append(" | ").append(warning).append(" | ").append(suggestion).append(" |\n");
        if (model != null) {
            sb.append("\n<sub>").append(model);
            if (costUsd != null && costUsd.signum() > 0) {
                sb.append(" · $").append(costUsd.setScale(4, RoundingMode.HALF_UP).toPlainString());
            }
            sb.append("</sub>\n");
        }
        return sb.toString();
    }

    private static long count(List<ReviewComment> comments, Severity s) {
        return comments.stream().filter(c -> c.severity() == s).count();
    }
}
