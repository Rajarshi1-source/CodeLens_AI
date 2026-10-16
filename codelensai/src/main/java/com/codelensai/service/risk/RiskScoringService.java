package com.codelensai.service.risk;

import com.codelensai.config.RiskProperties;
import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.enums.RiskLevel;
import com.codelensai.model.enums.Severity;
import com.codelensai.util.DiffParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * G3 PR risk score: deterministic, explainable heuristics (the LLM only writes the prose summary). Each
 * factor contributes points and a human-readable reason; the total is clamped to 0-100 and banded
 * HIGH (>= high-threshold) / MEDIUM (>= medium-threshold) / LOW.
 *
 * <table>
 *   <tr><td>sensitive paths (auth, security, crypto, payments, migrations, config, CI)</td><td>+25 first, +5 each more, max 35</td></tr>
 *   <tr><td>production change with no test changes</td><td>+15</td></tr>
 *   <tr><td>test lines &ge; 30% of production lines</td><td>-10</td></tr>
 *   <tr><td>size &gt; 500 / &gt; 200 changed lines</td><td>+15 / +8</td></tr>
 *   <tr><td>max fan-in of a changed symbol &ge; 10 / &ge; 3 (from the G1 index)</td><td>+15 / +8</td></tr>
 *   <tr><td>each CRITICAL / WARNING finding</td><td>+12 (max 36) / +4 (max 16)</td></tr>
 * </table>
 */
@Service
public class RiskScoringService {

    private static final Pattern TEST_PATH = Pattern.compile(
            "(?i)(^|/)(test|tests|spec|__tests__)/|(Test|Tests|Spec|IT)\\.[a-z]+$|\\.(test|spec)\\.[a-z]+$|_test\\.[a-z]+$|(^|/)test_[^/]+$");

    private final RiskProperties props;
    private final List<Pattern> sensitive;

    public RiskScoringService(RiskProperties props) {
        this.props = props;
        this.sensitive = props.sensitivePaths().stream().map(Pattern::compile).toList();
    }

    public record Input(List<DiffParser.FileDiff> files, List<ReviewComment> comments, Map<String, Integer> callerCounts) {
        public Input {
            files = files == null ? List.of() : files;
            comments = comments == null ? List.of() : comments;
            callerCounts = callerCounts == null ? Map.of() : callerCounts;
        }
    }

    public RiskAssessment score(Input in) {
        List<RiskAssessment.Reason> reasons = new ArrayList<>();

        List<String> sensitiveFiles = in.files().stream().map(DiffParser.FileDiff::path)
                .filter(p -> sensitive.stream().anyMatch(s -> s.matcher(p).find())).toList();
        if (!sensitiveFiles.isEmpty()) {
            int pts = Math.min(35, 25 + 5 * (sensitiveFiles.size() - 1));
            reasons.add(new RiskAssessment.Reason("SENSITIVE_PATHS",
                    "Touches sensitive code: " + String.join(", ", sensitiveFiles.stream().limit(4).toList())
                            + (sensitiveFiles.size() > 4 ? " (+" + (sensitiveFiles.size() - 4) + " more)" : ""), pts));
        }

        int prodLines = 0;
        int testLines = 0;
        int changed = 0;
        for (DiffParser.FileDiff f : in.files()) {
            int[] counts = changedLines(f);
            changed += counts[0] + counts[1];
            if (TEST_PATH.matcher(f.path()).find()) {
                testLines += counts[0];
            } else {
                prodLines += counts[0];
            }
        }
        if (prodLines > 0 && testLines == 0) {
            reasons.add(new RiskAssessment.Reason("NO_TESTS",
                    "No tests added or changed (+" + prodLines + " production lines)", 15));
        } else if (prodLines > 0 && testLines >= 0.3 * prodLines) {
            reasons.add(new RiskAssessment.Reason("TESTS_INCLUDED",
                    "Tests accompany the change (" + testLines + " test lines)", -10));
        }

        if (changed > 500) {
            reasons.add(new RiskAssessment.Reason("LARGE_DIFF", "Large change: " + changed + " lines", 15));
        } else if (changed > 200) {
            reasons.add(new RiskAssessment.Reason("MEDIUM_DIFF", "Sizeable change: " + changed + " lines", 8));
        }

        in.callerCounts().entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(top -> {
            if (top.getValue() >= 10) {
                reasons.add(new RiskAssessment.Reason("HIGH_FAN_IN",
                        "Modifies `" + top.getKey() + "`, which has " + top.getValue() + " callers", 15));
            } else if (top.getValue() >= 3) {
                reasons.add(new RiskAssessment.Reason("FAN_IN",
                        "Modifies `" + top.getKey() + "`, which has " + top.getValue() + " callers", 8));
            }
        });

        long critical = in.comments().stream().filter(c -> c.severity() == Severity.CRITICAL).count();
        long warning = in.comments().stream().filter(c -> c.severity() == Severity.WARNING).count();
        if (critical > 0) {
            reasons.add(new RiskAssessment.Reason("CRITICAL_FINDINGS",
                    critical + " CRITICAL finding(s)", (int) Math.min(36, 12 * critical)));
        }
        if (warning > 0) {
            reasons.add(new RiskAssessment.Reason("WARNING_FINDINGS",
                    warning + " WARNING finding(s)", (int) Math.min(16, 4 * warning)));
        }

        int score = Math.max(0, Math.min(100, reasons.stream().mapToInt(RiskAssessment.Reason::points).sum()));
        RiskLevel level = score >= props.highThreshold() ? RiskLevel.HIGH
                : score >= props.mediumThreshold() ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new RiskAssessment(score, level, reasons, recommendation(level, critical));
    }

    private static String recommendation(RiskLevel level, long critical) {
        return switch (level) {
            case HIGH -> critical > 0
                    ? "Request changes: resolve the CRITICAL findings and get a second reviewer before merging."
                    : "Request a careful second review before merging.";
            case MEDIUM -> "Review the flagged files carefully before merging.";
            case LOW -> "Low risk — a quick skim is sufficient.";
        };
    }

    /** {added, removed} line counts of a file diff. */
    static int[] changedLines(DiffParser.FileDiff f) {
        int add = 0;
        int del = 0;
        for (DiffParser.Hunk h : f.hunks()) {
            for (String line : h.text().split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    add++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    del++;
                }
            }
        }
        return new int[]{add, del};
    }
}
