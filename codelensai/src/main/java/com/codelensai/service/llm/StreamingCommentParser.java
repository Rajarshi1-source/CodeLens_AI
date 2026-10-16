package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.enums.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Incremental parser for the streamed structured output {@code {"comments":[{...},{...}]}}. Text deltas
 * are fed as they arrive; each comment object is emitted the moment its closing brace streams in, so the
 * UI shows the first finding while the model is still writing the rest (true streaming, not
 * "buffer the whole response then fan out").
 *
 * <p>A character scanner tracks string/escape state and a container stack; it captures objects that are
 * direct elements of the comments array (stack {@code { [}) or of a bare top-level array (stack {@code [}).
 * Leading prose or markdown fences before the first brace are ignored; a malformed object is skipped.
 * One instance per stream; not thread-safe.
 */
public final class StreamingCommentParser {

    private static final Logger log = LoggerFactory.getLogger(StreamingCommentParser.class);

    private final ObjectMapper mapper;
    private final DiffChunk chunk;
    private final Deque<Character> stack = new ArrayDeque<>();
    private final StringBuilder current = new StringBuilder();
    private boolean capturing;
    private boolean inString;
    private boolean escape;
    private int emitted;

    public StreamingCommentParser(ObjectMapper mapper, DiffChunk chunk) {
        this.mapper = mapper;
        this.chunk = chunk;
    }

    public List<ReviewEvent.Finding> feed(String delta) {
        List<ReviewEvent.Finding> out = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return out;
        }
        for (int i = 0; i < delta.length(); i++) {
            char c = delta.charAt(i);
            if (capturing) {
                current.append(c);
            }
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (!stack.isEmpty()) {
                        inString = true;
                    }
                }
                case '{' -> {
                    if (!capturing && isElementPosition()) {
                        capturing = true;
                        current.setLength(0);
                        current.append(c);
                    }
                    stack.push('{');
                }
                case '[' -> stack.push('[');
                case '}', ']' -> {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    if (c == '}' && capturing && isElementPosition()) {
                        capturing = false;
                        ReviewEvent.Finding f = toFinding(current.toString());
                        if (f != null) {
                            out.add(f);
                            emitted++;
                        }
                        current.setLength(0);
                    }
                }
                default -> {
                    // structural whitespace, commas, colons, scalars: nothing to track
                }
            }
        }
        return out;
    }

    /** Discard any partial state (a retry / fallback restarted the output). */
    public void reset() {
        stack.clear();
        current.setLength(0);
        capturing = false;
        inString = false;
        escape = false;
    }

    public int emitted() {
        return emitted;
    }

    /** True when the next '{' (or a just-closed '}') sits directly inside the comments array. */
    private boolean isElementPosition() {
        if (stack.size() == 1) {
            return stack.peek() == '[';
        }
        if (stack.size() == 2) {
            Character top = stack.peek();
            Character bottom = stack.peekLast();
            return top == '[' && bottom == '{';
        }
        return false;
    }

    private ReviewEvent.Finding toFinding(String json) {
        try {
            JsonNode c = mapper.readTree(json);
            String text = c.path("comment").asString("");
            if (text.isBlank()) {
                return null;
            }
            String file = c.path("file").asString("");
            if (file.isBlank() && chunk != null) {
                file = chunk.fileName();
            }
            int line = c.path("line").asInt(chunk == null ? 0 : chunk.startLine());
            JsonNode suggestion = c.path("suggestion");
            return new ReviewEvent.Finding(
                    file,
                    Math.max(0, line),
                    parseSeverity(c.path("severity").asString("SUGGESTION")),
                    text.trim(),
                    suggestion.isString() ? suggestion.asString() : null,
                    c.path("confidence").asDouble(0.6),
                    ReviewOutputSchema.ruleKeyFor(c.path("category").asString(null)));
        } catch (Exception e) {
            log.warn("Skipping malformed streamed comment for {}: {}", chunk == null ? "?" : chunk.fileName(), e.getMessage());
            return null;
        }
    }

    private static Severity parseSeverity(String raw) {
        try {
            return Severity.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return Severity.SUGGESTION;
        }
    }
}
