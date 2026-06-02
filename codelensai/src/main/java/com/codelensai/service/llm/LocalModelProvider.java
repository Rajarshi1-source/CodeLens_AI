package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewToken;
import com.codelensai.model.enums.Severity;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Offline, deterministic review provider used as the MVP default ({@code codelens.llm.provider=local}).
 * It does NOT call any external model — it runs a handful of static heuristics over the added lines of
 * a diff chunk and emits structured {@link ReviewToken}s word-by-word so the whole streaming pipeline
 * (Redis -> WebSocket -> UI) can be demoed and eval-tested without an API key. Real frontier providers
 * implement the same {@link LlmReviewProvider} interface and slot in via config.
 */
public final class LocalModelProvider implements LlmReviewProvider {

    private record Rule(Pattern pattern, Severity severity, double confidence, String message) {
    }

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?i)(api[_-]?key|secret|password|access[_-]?token)\\s*[:=]\\s*[\"'][^\"']{4,}"),
                    Severity.CRITICAL, 0.92,
                    "Hardcoded secret detected. Move this credential to an environment variable or secrets manager; never commit it to source."),
            new Rule(Pattern.compile("(?i)\"\\s*sk-[a-z0-9-]{6,}"),
                    Severity.CRITICAL, 0.9,
                    "This looks like a live API key committed in source. Rotate it immediately and load it from configuration instead."),
            new Rule(Pattern.compile("(?i)(select|insert|update|delete)\\b.*(\"\\s*\\+|\\+\\s*\"|'\\s*\\+)"),
                    Severity.CRITICAL, 0.85,
                    "Possible SQL injection: the query is built by string concatenation. Use a parameterized/prepared statement instead."),
            new Rule(Pattern.compile("\\b\\w+\\.get\\w*\\([^)]*\\)\\.\\w+\\s*\\("),
                    Severity.CRITICAL, 0.6,
                    "Possible null dereference: the result may be null before this method call. Add a null check or use Optional."),
            new Rule(Pattern.compile("\\b\\w+\\+\\+\\s*;"),
                    Severity.WARNING, 0.6,
                    "Non-atomic update on shared mutable state. If this can be accessed concurrently, use synchronization or an atomic type."),
            new Rule(Pattern.compile("(\\+\\s*1\\s*\\]|\\bsize\\s*\\+\\s*1\\b)"),
                    Severity.WARNING, 0.55,
                    "Potential off-by-one error in this range/index calculation. Double-check the inclusive/exclusive bounds."),
            new Rule(Pattern.compile("(?i)(Files\\.read|readString\\(|\\.read\\w*\\()"),
                    Severity.WARNING, 0.5,
                    "I/O call without visible error handling. Wrap this in try/catch or document who handles the checked exception.")
    );

    @Override
    public Flux<ReviewToken> streamReview(ReviewPromptContext ctx) {
        DiffChunk chunk = ctx.chunk();
        return Flux.fromIterable(buildTokens(chunk));
    }

    List<ReviewToken> buildTokens(DiffChunk chunk) {
        List<ReviewToken> tokens = new ArrayList<>();
        if (chunk == null || chunk.content() == null) {
            return tokens;
        }

        int currentNewLine = chunk.startLine() > 0 ? chunk.startLine() : 1;
        for (String raw : chunk.content().split("\n", -1)) {
            var header = HUNK_HEADER.matcher(raw);
            if (header.matches()) {
                currentNewLine = Integer.parseInt(header.group(1));
                continue;
            }
            if (raw.startsWith("+++") || raw.startsWith("---")) {
                continue;
            }
            if (raw.startsWith("+")) {
                String code = raw.substring(1);
                emitForLine(tokens, chunk.fileName(), currentNewLine, code);
                currentNewLine++;
            } else if (raw.startsWith("-")) {
                // removed line: no new-file line number consumed
            } else {
                // context (or blank) line
                currentNewLine++;
            }
        }
        return tokens;
    }

    private void emitForLine(List<ReviewToken> tokens, String file, int line, String code) {
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(code).find()) {
                streamWords(tokens, file, line, rule.severity(), rule.message());
                return; // one comment per line keeps the mock output clean
            }
        }
    }

    /** Split the message into word fragments so the client renders it token-by-token. */
    private void streamWords(List<ReviewToken> tokens, String file, int line, Severity severity, String message) {
        String[] words = message.split(" ");
        for (int i = 0; i < words.length; i++) {
            String fragment = i == 0 ? words[i] : " " + words[i];
            tokens.add(new ReviewToken(file, line, severity, fragment));
        }
    }

    @Override
    public String providerId() {
        return "local-mock";
    }
}
