package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.enums.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelProviderTest {

    private final LocalModelProvider provider = new LocalModelProvider();

    @Test
    void flagsHardcodedSecretAsCriticalOnTheCorrectLineWithASuggestion() {
        String content = """
                @@ -1,2 +1,4 @@
                 import os
                +API_KEY = "sk-live-9f8a7b6c5d4e3f2a1b0c"
                +DB_URL = os.environ["DB_URL"]
                """;
        DiffChunk chunk = new DiffChunk("config.py", 1, content, "python");
        List<ReviewEvent.Finding> findings = provider.buildFindings(chunk);
        assertTrue(!findings.isEmpty(), "expected a finding");
        ReviewEvent.Finding first = findings.get(0);
        assertEquals("config.py", first.file());
        assertEquals(2, first.line()); // line 1 = "import os" (context), line 2 = API_KEY (added)
        assertEquals(Severity.CRITICAL, first.severity());
        assertEquals("local.hardcoded-secret", first.ruleKey());
        assertEquals("API_KEY = os.environ[\"API_KEY\"]", first.codeSuggestion());
    }

    @Test
    void javaSecretSuggestionReadsTheEnvironment() {
        String suggestion = LocalModelProvider.envSuggestion("    private static final String apiKey = \"sk-live-abcdef123\";");
        assertEquals("    private static final String apiKey = System.getenv(\"API_KEY\");", suggestion);
    }

    @Test
    void sqlConcatenationGetsAPlaceholderSuggestion() {
        String suggestion = LocalModelProvider.sqlSuggestion("        String sql = \"SELECT * FROM users WHERE email = '\" + email + \"'\";");
        assertNotNull(suggestion);
        assertTrue(suggestion.contains("\"SELECT * FROM users WHERE email = ?\""), suggestion);
    }

    @Test
    void cleanDiffProducesNoFindings() {
        String content = """
                @@ -1,2 +1,4 @@
                 public class Greeting {
                +    public String hello(String name) {
                +        return name == null ? "Hi" : "Hi " + name;
                 }
                """;
        assertTrue(provider.buildFindings(new DiffChunk("Greeting.java", 1, content, "java")).isEmpty());
    }

    @Test
    void streamEmitsFindingsThenAnEstimatedUsage() {
        String content = """
                @@ -1,1 +1,2 @@
                +String sql = "SELECT * FROM t WHERE x = '" + q + "'";
                """;
        DiffChunk chunk = new DiffChunk("Dao.java", 1, content, "java");
        List<ReviewEvent> events = provider.streamReview(ReviewPromptContext.of(chunk, "system")).collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e instanceof ReviewEvent.Finding f && f.severity() == Severity.CRITICAL));
        ReviewEvent.Usage usage = assertInstanceOf(ReviewEvent.Usage.class, events.get(events.size() - 1));
        assertTrue(usage.estimated());
        assertTrue(usage.inputTokens() > 0);
        assertEquals("local-mock", usage.model());
    }
}
