package com.codelensai.service.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashingEmbeddingProviderTest {

    private final HashingEmbeddingProvider embedder = new HashingEmbeddingProvider(512);

    @Test
    void deterministicUnitVectorsOfTheSchemaDimension() {
        float[] a = embedder.embed("public BigDecimal calculateTotal(List<LineItem> items)");
        float[] b = embedder.embed("public BigDecimal calculateTotal(List<LineItem> items)");
        assertEquals(512, a.length);
        assertArrayEquals(a, b);
        double norm = 0;
        for (float x : a) {
            norm += x * x;
        }
        assertEquals(1.0, norm, 1e-5);
    }

    @Test
    void sharedVocabularyIsCloserThanUnrelatedCode() {
        float[] price = embedder.embed("BigDecimal total = priceCalculator.calculateTotal(cart.lineItems());");
        float[] related = embedder.embed("public BigDecimal calculateTotal(List<LineItem> lineItems) { return sum(lineItems); }");
        float[] unrelated = embedder.embed("def render_template(request): return html.escape(request.path)");
        assertTrue(EmbeddingProvider.cosine(price, related) > EmbeddingProvider.cosine(price, unrelated));
    }

    @Test
    void splitsCamelAndSnakeCase() {
        assertEquals(java.util.List.of("calculate", "total", "line", "items"),
                HashingEmbeddingProvider.tokens("calculateTotal(line_items)"));
    }

    @Test
    void vectorLiteralIsPgvectorSyntax() {
        assertEquals("[0.5,-1.0]", EmbeddingProvider.toVectorLiteral(new float[]{0.5f, -1f}));
    }
}
