package com.codelensai.service.risk;

import com.codelensai.config.RiskProperties;
import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.enums.RiskLevel;
import com.codelensai.model.enums.Severity;
import com.codelensai.util.DiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScoringServiceTest {

    private final RiskScoringService risk = new RiskScoringService(new RiskProperties(null, null, null));

    private static DiffParser.FileDiff file(String path, int added) {
        StringBuilder sb = new StringBuilder("@@ -1,1 +1," + (added + 1) + " @@\n context\n");
        for (int i = 0; i < added; i++) {
            sb.append("+line ").append(i).append('\n');
        }
        return new DiffParser.FileDiff(path, "java", List.of(new DiffParser.Hunk(1, sb.toString())));
    }

    private static ReviewComment comment(Severity s, int line) {
        return new ReviewComment("src/A.java", line, s, "x", null, 0.9, "local.x", null);
    }

    private static int points(RiskAssessment a, String code) {
        return a.reasons().stream().filter(r -> r.code().equals(code)).mapToInt(RiskAssessment.Reason::points).sum();
    }

    @Test
    void authChangeWithoutTestsAndCriticalsIsHigh() {
        RiskAssessment a = risk.score(new RiskScoringService.Input(
                List.of(file("src/main/java/com/shop/auth/LoginService.java", 12)),
                List.of(comment(Severity.CRITICAL, 2), comment(Severity.CRITICAL, 3)), Map.of()));
        assertEquals(25, points(a, "SENSITIVE_PATHS"));
        assertEquals(15, points(a, "NO_TESTS"));
        assertEquals(24, points(a, "CRITICAL_FINDINGS"));
        assertEquals(64, a.score());
        assertEquals(RiskLevel.HIGH, a.level());
        assertTrue(a.recommendedAction().startsWith("Request changes"));
    }

    @Test
    void testsAccompanyingTheChangeLowerTheScore() {
        RiskAssessment a = risk.score(new RiskScoringService.Input(
                List.of(file("src/main/java/Util.java", 10), file("src/test/java/UtilTest.java", 8)), List.of(), Map.of()));
        assertEquals(-10, points(a, "TESTS_INCLUDED"));
        assertEquals(0, a.score(), "clamped at zero");
        assertEquals(RiskLevel.LOW, a.level());
    }

    @Test
    void bandBoundaries() {
        // 25 (sensitive) + 15 (no tests) + 8 (fan-in 3) + 12 (1 critical) = 60 -> HIGH
        RiskAssessment high = risk.score(new RiskScoringService.Input(
                List.of(file("src/payment/PriceCalculator.java", 3)), List.of(comment(Severity.CRITICAL, 1)),
                Map.of("calculateTotal", 3)));
        assertEquals(60, high.score());
        assertEquals(RiskLevel.HIGH, high.level());
        // same without the fan-in -> 52 -> MEDIUM
        RiskAssessment medium = risk.score(new RiskScoringService.Input(
                List.of(file("src/payment/PriceCalculator.java", 3)), List.of(comment(Severity.CRITICAL, 1)), Map.of()));
        assertEquals(52, medium.score());
        assertEquals(RiskLevel.MEDIUM, medium.level());
    }

    @Test
    void sizeAndFanInAndCaps() {
        RiskAssessment a = risk.score(new RiskScoringService.Input(
                List.of(file("src/A.java", 600)),
                List.of(comment(Severity.CRITICAL, 1), comment(Severity.CRITICAL, 2), comment(Severity.CRITICAL, 3),
                        comment(Severity.CRITICAL, 4), comment(Severity.WARNING, 5), comment(Severity.WARNING, 6),
                        comment(Severity.WARNING, 7), comment(Severity.WARNING, 8), comment(Severity.WARNING, 9)),
                Map.of("run", 12)));
        assertEquals(15, points(a, "LARGE_DIFF"));
        assertEquals(15, points(a, "HIGH_FAN_IN"));
        assertEquals(36, points(a, "CRITICAL_FINDINGS"), "capped");
        assertEquals(16, points(a, "WARNING_FINDINGS"), "capped");
        assertEquals(97, a.score());
    }

    @Test
    void aCriticalFindingIsNeverLowRisk() {
        // 15 (no tests) + 12 (1 critical) = 27 -> lifted to the MEDIUM threshold by an explicit reason
        RiskAssessment a = risk.score(new RiskScoringService.Input(
                List.of(file("src/main/java/Config.java", 1)), List.of(comment(Severity.CRITICAL, 2)), Map.of()));
        assertEquals(3, points(a, "CRITICAL_FLOOR"));
        assertEquals(30, a.score());
        assertEquals(RiskLevel.MEDIUM, a.level());
        assertEquals(a.score(), a.reasons().stream().mapToInt(RiskAssessment.Reason::points).sum(), "reasons sum to the score");

        // warnings alone get no floor
        RiskAssessment w = risk.score(new RiskScoringService.Input(
                List.of(file("src/main/java/Config.java", 1)), List.of(comment(Severity.WARNING, 2)), Map.of()));
        assertEquals(RiskLevel.LOW, w.level());
        assertEquals(0, points(w, "CRITICAL_FLOOR"));
    }

    @Test
    void ciAndMigrationsAreSensitive() {
        RiskAssessment a = risk.score(new RiskScoringService.Input(
                List.of(file(".github/workflows/ci.yml", 1), file("db/migrations/V2__users.sql", 1)), List.of(), Map.of()));
        assertEquals(30, points(a, "SENSITIVE_PATHS"));
    }
}
