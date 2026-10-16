package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.enums.Severity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingCommentParserTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final DiffChunk CHUNK = new DiffChunk("src/A.java", 10, "", "java");

    private static final String DOC = """
            {"comments":[
              {"file":"src/A.java","line":12,"severity":"CRITICAL","category":"security",
               "comment":"Escaped \\"quote\\" and a brace { inside } a string","suggestion":"x = 1;","confidence":0.9},
              {"file":"src/A.java","line":14,"severity":"WARNING","category":"concurrency",
               "comment":"Second finding","suggestion":null,"confidence":0.7,"extra":{"nested":[1,2,{"k":"v"}]}}
            ]}""";

    @Test
    void emitsEachCommentAsSoonAsItCloses() {
        StreamingCommentParser parser = new StreamingCommentParser(MAPPER, CHUNK);
        int firstClose = DOC.indexOf("\"confidence\":0.9}") + "\"confidence\":0.9}".length();
        List<ReviewEvent.Finding> first = parser.feed(DOC.substring(0, firstClose));
        assertEquals(1, first.size(), "the first comment must be emitted before the stream ends");
        assertEquals(1, parser.feed(DOC.substring(firstClose)).size());
    }

    @Test
    void identicalResultWhateverTheSplitPoint() {
        List<ReviewEvent.Finding> whole = new StreamingCommentParser(MAPPER, CHUNK).feed(DOC);
        assertEquals(2, whole.size());
        for (int i = 0; i <= DOC.length(); i++) {
            StreamingCommentParser p = new StreamingCommentParser(MAPPER, CHUNK);
            List<ReviewEvent.Finding> out = new ArrayList<>(p.feed(DOC.substring(0, i)));
            out.addAll(p.feed(DOC.substring(i)));
            assertEquals(whole, out, "split at " + i);
        }
    }

    @Test
    void mapsFieldsAndRuleKeys() {
        List<ReviewEvent.Finding> out = new StreamingCommentParser(MAPPER, CHUNK).feed(DOC);
        ReviewEvent.Finding a = out.get(0);
        assertEquals(12, a.line());
        assertEquals(Severity.CRITICAL, a.severity());
        assertEquals("llm.security", a.ruleKey());
        assertEquals("x = 1;", a.codeSuggestion());
        assertTrue(a.text().contains("\"quote\""));
        ReviewEvent.Finding b = out.get(1);
        assertNull(b.codeSuggestion());
        assertEquals("llm.concurrency", b.ruleKey());
    }

    @Test
    void toleratesFencesAndSkipsMalformedObjects() {
        String fenced = "```json\n{\"comments\":[{\"line\":\"not-a-number\",\"comment\":\"\"},"
                + "{\"line\":11,\"severity\":\"bogus\",\"comment\":\"ok\"}]}\n```";
        List<ReviewEvent.Finding> out = new StreamingCommentParser(MAPPER, CHUNK).feed(fenced);
        assertEquals(1, out.size());
        assertEquals("src/A.java", out.get(0).file(), "missing file falls back to the chunk's file");
        assertEquals(Severity.SUGGESTION, out.get(0).severity());
        assertEquals("llm.general", out.get(0).ruleKey());
    }

    @Test
    void resetDiscardsAPartialObject() {
        StreamingCommentParser parser = new StreamingCommentParser(MAPPER, CHUNK);
        parser.feed("{\"comments\":[{\"line\":3,\"comment\":\"half");
        parser.reset();
        List<ReviewEvent.Finding> out = parser.feed("{\"comments\":[{\"line\":4,\"comment\":\"whole\"}]}");
        assertEquals(1, out.size());
        assertEquals(4, out.get(0).line());
    }
}
