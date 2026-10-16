package com.codelensai.service.risk;

import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.dto.SummaryContext;
import com.codelensai.model.dto.SummaryResult;
import com.codelensai.service.AIReviewService;
import com.codelensai.service.llm.LocalModelProvider;
import com.codelensai.service.llm.SummaryPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * G3 review summary: a short prose explanation of the review on the provider's summary model, with the
 * deterministic template as a fallback. The top findings (by severity, then confidence) are the input.
 */
@Service
public class ReviewSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSummaryService.class);
    private static final int TOP_FINDINGS = 12;

    private final AIReviewService aiReviewService;

    public ReviewSummaryService(AIReviewService aiReviewService) {
        this.aiReviewService = aiReviewService;
    }

    public SummaryResult summarize(String prTitle, List<String> files, List<ReviewComment> comments, RiskAssessment risk) {
        List<ReviewComment> top = comments.stream()
                .sorted(Comparator.comparing(ReviewComment::severity).thenComparing(c -> -c.confidence()))
                .limit(TOP_FINDINGS)
                .toList();
        SummaryContext ctx = new SummaryContext(prTitle, files, top, risk, SummaryPrompt.SYSTEM);
        try {
            SummaryResult r = aiReviewService.summarize(ctx).block(Duration.ofSeconds(90));
            if (r != null && r.text() != null && !r.text().isBlank()) {
                return r;
            }
        } catch (RuntimeException e) {
            log.info("Summary generation failed ({}); using template", e.getMessage());
        }
        return new SummaryResult(LocalModelProvider.templateSummary(ctx), null);
    }
}
