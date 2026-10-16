package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.enums.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalContextRulesTest {

    private static final String SIGNATURE_CHANGE = """
            @@ -20,7 +20,7 @@ public class PriceCalculator {
                 private final TaxTable taxes;
            -    public BigDecimal calculateTotal(List<LineItem> items) {
            +    public BigDecimal calculateTotal(List<LineItem> items, String discountCode) {
                     BigDecimal total = BigDecimal.ZERO;
            """;

    private static final String CALLER = """
            // src/main/java/com/shop/CartController.java:30-34 (checkout)
                public Receipt checkout(Cart cart) {
                    BigDecimal total = priceCalculator.calculateTotal(cart.items());
                    return new Receipt(total);
                }""";

    @Test
    void callerWithOldArityIsCritical() {
        DiffChunk chunk = new DiffChunk("src/main/java/com/shop/PriceCalculator.java", 20, SIGNATURE_CHANGE, "java");
        List<ReviewEvent.Finding> out = LocalContextRules.evaluate(
                new ReviewPromptContext(chunk, List.of(CALLER), List.of(), "sys"));
        assertEquals(1, out.size());
        ReviewEvent.Finding f = out.get(0);
        assertEquals("local.caller-arity", f.ruleKey());
        assertEquals(Severity.CRITICAL, f.severity());
        assertEquals(21, f.line(), "anchored on the new signature line");
        assertTrue(f.text().contains("CartController.java"), f.text());
    }

    @Test
    void nothingWithoutRetrievedContext() {
        DiffChunk chunk = new DiffChunk("PriceCalculator.java", 20, SIGNATURE_CHANGE, "java");
        assertTrue(LocalContextRules.evaluate(ReviewPromptContext.of(chunk, "sys")).isEmpty());
    }

    @Test
    void callerAlreadyUpdatedIsFine() {
        String updated = CALLER.replace("calculateTotal(cart.items())", "calculateTotal(cart.items(), cart.code())");
        DiffChunk chunk = new DiffChunk("PriceCalculator.java", 20, SIGNATURE_CHANGE, "java");
        assertTrue(LocalContextRules.evaluate(new ReviewPromptContext(chunk, List.of(updated), List.of(), "sys")).isEmpty());
    }

    @Test
    void removedMethodStillCalledIsCritical() {
        String diff = """
                @@ -40,6 +40,3 @@ public class Legacy {
                -    public void flushCache(String region) {
                -        cache.clear(region);
                -    }
                     public void other() {
                """;
        String caller = "// src/Jobs.java:1-3 (nightly)\n    void nightly() { legacy.flushCache(\"all\"); }";
        DiffChunk chunk = new DiffChunk("src/Legacy.java", 40, diff, "java");
        List<ReviewEvent.Finding> out = LocalContextRules.evaluate(new ReviewPromptContext(chunk, List.of(caller), List.of(), "s"));
        assertEquals(1, out.size());
        assertEquals("local.removed-still-called", out.get(0).ruleKey());
    }

    @Test
    void newNullReturnDereferencedByCallerIsAWarning() {
        String diff = """
                @@ -10,5 +10,8 @@ public class ReportExporter {
                     public Report findReport(String id) {
                +        if (id == null) {
                +            return null;
                +        }
                         return store.load(id);
                     }
                """;
        String caller = "// src/ReportController.java:5-7 (show)\n    String show(String id) {\n        return exporter.findReport(id).render();\n    }";
        DiffChunk chunk = new DiffChunk("src/ReportExporter.java", 10, diff, "java");
        List<ReviewEvent.Finding> out = LocalContextRules.evaluate(new ReviewPromptContext(chunk, List.of(caller), List.of(), "s"));
        assertEquals(1, out.size());
        assertEquals("local.null-return-deref", out.get(0).ruleKey());
        assertEquals(12, out.get(0).line());
        assertEquals(Severity.WARNING, out.get(0).severity());
    }
}
