package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewToken;
import com.codelensai.model.enums.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelProviderTest {

    private final LocalModelProvider provider = new LocalModelProvider();

    @Test
    void flagsHardcodedSecretAsCriticalOnTheCorrectLine() {
        String content = """
                @@ -1,2 +1,4 @@
                 import os
                +API_KEY = "sk-live-9f8a7b6c5d4e3f2a1b0c"
                +DB_URL = os.environ["DB_URL"]
                """;
        DiffChunk chunk = new DiffChunk("config.py", 1, content, "python");

        List<ReviewToken> tokens = provider.buildTokens(chunk);

        assertFalseEmpty(tokens);
        ReviewToken first = tokens.get(0);
        assertEquals("config.py", first.file());
        assertEquals(2, first.line()); // line 1 = "import os" (context), line 2 = API_KEY (added)
        assertEquals(Severity.CRITICAL, first.severity());
    }

    @Test
    void cleanDiffProducesNoComments() {
        String content = """
                @@ -1,2 +1,4 @@
                 public class Greeting {
                +    public String hello(String name) {
                +        return name == null ? "Hi" : "Hi " + name;
                 }
                """;
        DiffChunk chunk = new DiffChunk("Greeting.java", 1, content, "java");

        assertTrue(provider.buildTokens(chunk).isEmpty());
    }

    @Test
    void streamReviewFluxCompletes() {
        String content = """
                @@ -1,1 +1,2 @@
                +String sql = "SELECT * FROM t WHERE x = '" + q + "'";
                """;
        DiffChunk chunk = new DiffChunk("Dao.java", 1, content, "java");

        List<ReviewToken> collected = provider.streamReview(ReviewPromptContext.of(chunk, "system"))
                .collectList().block();

        assertNotNull(collected);
        assertTrue(collected.stream().anyMatch(t -> t.severity() == Severity.CRITICAL));
    }

    private static void assertFalseEmpty(List<?> list) {
        assertTrue(list != null && !list.isEmpty(), "expected non-empty token list");
    }
}
