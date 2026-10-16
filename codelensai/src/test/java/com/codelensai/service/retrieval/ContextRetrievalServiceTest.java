package com.codelensai.service.retrieval;

import com.codelensai.config.RetrievalProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.service.DiffChunkerService;
import com.codelensai.service.code.CodeStructureParsers;
import com.codelensai.service.code.FileContentProvider;
import com.codelensai.service.embedding.HashingEmbeddingProvider;
import com.codelensai.service.index.CodeChunker;
import com.codelensai.service.llm.LocalModelProvider;
import com.codelensai.util.DiffParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRetrievalServiceTest {

    static final String CALLER_PATH = "src/main/java/shop/checkout/CartController.java";
    static final Map<String, String> FILES = Map.of(
            CALLER_PATH, """
                    package shop.checkout;

                    public class CartController {
                        private final PriceCalculator priceCalculator = new PriceCalculator();

                        public Receipt checkout(Cart cart) {
                            java.math.BigDecimal total = priceCalculator.calculateTotal(cart.items());
                            return new Receipt(total);
                        }
                    }
                    """,
            "src/test/java/shop/payment/PriceCalculatorTest.java", """
                    class PriceCalculatorTest {
                        @Test
                        void sums() {
                            assertEquals(3, new PriceCalculator().calculateTotal(List.of(a, b)));
                        }
                    }
                    """);

    static final String DIFF = """
            --- a/src/main/java/shop/payment/PriceCalculator.java
            +++ b/src/main/java/shop/payment/PriceCalculator.java
            @@ -2,6 +2,6 @@ package shop.payment;
             public class PriceCalculator {
            -    public java.math.BigDecimal calculateTotal(java.util.List<LineItem> items) {
            +    public java.math.BigDecimal calculateTotal(java.util.List<LineItem> items, String discountCode) {
                     return items.stream().map(LineItem::price).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                 }
             }
            """;

    private final DiffParser diffParser = new DiffParser();
    private final CodeChunker chunker = new CodeChunker(new CodeStructureParsers(diffParser));
    private final ContextRetrievalService retrieval = new ContextRetrievalService(
            new RetrievalProperties(null, null, null, null, null, null), null, null,
            new HashingEmbeddingProvider(512), chunker, new SimpleMeterRegistry());

    @Test
    void retrievesCallersTestsAndRelatedWithinBudget() {
        List<DiffChunk> chunks = new DiffChunkerService(diffParser).chunk(DIFF, 4000, FileContentProvider.none());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).symbols()).contains("calculateTotal");

        ContextSource source = retrieval.inMemory(FILES);
        RetrievedContext ctx = retrieval.retrieveAll(source, chunks, Set.of(chunks.get(0).fileName())).get(0);
        assertThat(ctx.callers()).anySatisfy(c -> assertThat(c).startsWith("// " + CALLER_PATH).contains("calculateTotal(cart.items())"));
        assertThat(ctx.tests()).anySatisfy(t -> assertThat(t).contains("PriceCalculatorTest"));
        assertThat(ctx.estimatedTokens()).isPositive().isLessThanOrEqualTo(1500);

        List<ReviewEvent> events = new LocalModelProvider().streamReview(new ReviewPromptContext(chunks.get(0),
                ctx.callers(), ctx.tests(), "sys", ctx.related())).collectList().block();
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(ReviewEvent.Finding.class,
                f -> assertThat(f.ruleKey()).isEqualTo("local.caller-arity")));
    }

    @Test
    void changedFilesAreNeverReturnedAsContext() {
        ContextSource source = retrieval.inMemory(FILES);
        assertThat(source.callers(Set.of("calculateTotal"), Set.of(), 10))
                .extracting(s -> s.path()).contains(CALLER_PATH);
        assertThat(source.callers(Set.of("calculateTotal"), Set.of(CALLER_PATH), 10))
                .extracting(s -> s.path()).doesNotContain(CALLER_PATH);
    }

    @Test
    void emptySourceMeansNoContext() {
        List<DiffChunk> chunks = new DiffChunkerService(diffParser).chunk(DIFF, 4000);
        assertThat(retrieval.retrieveAll(ContextSource.EMPTY, chunks, Set.of()).get(0).snippetCount()).isZero();
    }
}
