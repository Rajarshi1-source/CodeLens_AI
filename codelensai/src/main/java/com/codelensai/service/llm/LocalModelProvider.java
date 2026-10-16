package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.SummaryContext;
import com.codelensai.model.dto.SummaryResult;
import com.codelensai.model.enums.Severity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, deterministic review provider used as the default ({@code codelens.llm.provider=local}).
 * It does NOT call any external model: it runs static heuristics over the added lines of a diff chunk
 * ({@code local.*} rules) plus {@link LocalContextRules} over the G1 retrieved callers, and emits
 * {@link ReviewEvent.Finding}s followed by an estimated {@link ReviewEvent.Usage}. That keeps the whole
 * pipeline — streaming, retrieval, risk, feedback, cost accounting and the eval gate — demoable and
 * reproducible without an API key.
 */
public final class LocalModelProvider implements LlmReviewProvider {

    /** Optional delay between emitted events so the demo shows findings streaming in (0 = none). */
    private final Duration pace;

    public LocalModelProvider() {
        this(Duration.ZERO);
    }

    public LocalModelProvider(Duration pace) {
        this.pace = pace == null || pace.isNegative() ? Duration.ZERO : pace;
    }

    public static final String MODEL = "local-mock";

    private record Rule(String id, Pattern pattern, Severity severity, double confidence, String message,
                        UnaryOperator<String> suggest) {
    }

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    private static final Pattern SECRET_ASSIGN = Pattern.compile(
            "(?i)^(\\s*)(?:(?:private|public|protected|static|final)\\s+)*(?:String\\s+|const\\s+|let\\s+|var\\s+)?"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*[:=]\\s*[\"'][^\"']{4,}[\"']\\s*;?\\s*$");

    private static final List<Rule> RULES = List.of(
            new Rule("local.hardcoded-secret",
                    Pattern.compile("(?i)(api[_-]?key|secret|password|access[_-]?token)\\s*[:=]\\s*[\"'][^\"']{4,}"),
                    Severity.CRITICAL, 0.92,
                    "Hardcoded secret detected. Move this credential to an environment variable or secrets manager; never commit it to source.",
                    LocalModelProvider::envSuggestion),
            new Rule("local.live-api-key", Pattern.compile("(?i)\"\\s*sk-[a-z0-9-]{6,}"),
                    Severity.CRITICAL, 0.9,
                    "This looks like a live API key committed in source. Rotate it immediately and load it from configuration instead.",
                    LocalModelProvider::envSuggestion),
            new Rule("local.sql-concat",
                    Pattern.compile("(?i)(select|insert|update|delete)\\b.*(\"\\s*\\+|\\+\\s*\"|'\\s*\\+)"),
                    Severity.CRITICAL, 0.85,
                    "Possible SQL injection: the query is built by string concatenation. Use a parameterized/prepared statement instead.",
                    LocalModelProvider::sqlSuggestion),
            new Rule("local.null-deref", Pattern.compile("\\b\\w+\\.get\\w*\\([^)]*\\)\\.\\w+\\s*\\("),
                    Severity.CRITICAL, 0.6,
                    "Possible null dereference: the result may be null before this method call. Add a null check or use Optional.",
                    null),
            new Rule("local.non-atomic", Pattern.compile("\\b\\w+\\+\\+\\s*;"),
                    Severity.WARNING, 0.6,
                    "Non-atomic update on shared mutable state. If this can be accessed concurrently, use synchronization or an atomic type.",
                    null),
            new Rule("local.off-by-one", Pattern.compile("(\\+\\s*1\\s*\\]|\\bsize\\s*\\+\\s*1\\b|<=\\s*\\w+\\.(length|size\\(\\)))"),
                    Severity.WARNING, 0.55,
                    "Potential off-by-one error in this range/index calculation. Double-check the inclusive/exclusive bounds.",
                    null),
            new Rule("local.io-unhandled", Pattern.compile("(?i)(Files\\.read|readString\\(|\\.read\\w*\\()"),
                    Severity.WARNING, 0.5,
                    "I/O call without visible error handling. Wrap this in try/catch or document who handles the checked exception.",
                    null)
    );

    @Override
    public Flux<ReviewEvent> streamReview(ReviewPromptContext ctx) {
        return Flux.defer(() -> {
            List<ReviewEvent.Finding> findings = new ArrayList<>(buildFindings(ctx.chunk()));
            findings.addAll(LocalContextRules.evaluate(ctx));
            long outputChars = findings.stream().mapToLong(f -> f.text().length()).sum();
            ReviewEvent.Usage usage = new ReviewEvent.Usage(providerId(), MODEL,
                    ReviewPromptRenderer.estimateTokens(ReviewPromptRenderer.userPrompt(ctx, true)),
                    outputChars / 4, true);
            List<ReviewEvent> events = new ArrayList<>(findings);
            events.add(usage);
            Flux<ReviewEvent> out = Flux.fromIterable(events);
            return pace.isZero() ? out : out.delayElements(pace);
        });
    }

    /** Regex rules over the chunk's added lines (one finding per line keeps the output clean). */
    List<ReviewEvent.Finding> buildFindings(DiffChunk chunk) {
        List<ReviewEvent.Finding> out = new ArrayList<>();
        if (chunk == null || chunk.content() == null) {
            return out;
        }
        int currentNewLine = chunk.startLine() > 0 ? chunk.startLine() : 1;
        for (String raw : chunk.content().split("\n", -1)) {
            Matcher header = HUNK_HEADER.matcher(raw);
            if (header.matches()) {
                currentNewLine = Integer.parseInt(header.group(1));
                continue;
            }
            if (raw.startsWith("+++") || raw.startsWith("---")) {
                continue;
            }
            if (raw.startsWith("+")) {
                String code = raw.substring(1);
                for (Rule rule : RULES) {
                    if (rule.pattern().matcher(code).find()) {
                        String suggestion = rule.suggest() == null ? null : rule.suggest().apply(code);
                        out.add(new ReviewEvent.Finding(chunk.fileName(), currentNewLine, rule.severity(),
                                rule.message(), suggestion, rule.confidence(), rule.id()));
                        break;
                    }
                }
                currentNewLine++;
            } else if (!raw.startsWith("-")) {
                currentNewLine++;
            }
        }
        return out;
    }

    /** {@code API_KEY = "sk-..."} → read the value from the environment, keeping the declaration shape. */
    static String envSuggestion(String code) {
        Matcher m = SECRET_ASSIGN.matcher(code);
        if (!m.matches()) {
            return null;
        }
        String indent = m.group(1);
        String name = m.group(2);
        String envName = name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
        String trimmed = code.trim();
        if (trimmed.contains("String ") || trimmed.endsWith(";")) {
            String decl = trimmed.substring(0, trimmed.indexOf(name)).trim();
            return indent + (decl.isEmpty() ? "" : decl + " ") + name + " = System.getenv(\"" + envName + "\");";
        }
        if (trimmed.startsWith("const ") || trimmed.startsWith("let ") || trimmed.startsWith("var ")) {
            String kw = trimmed.substring(0, trimmed.indexOf(' '));
            return indent + kw + " " + name + " = process.env." + envName + ";";
        }
        return indent + name + " = os.environ[\"" + envName + "\"]";
    }

    /** Rewrite a concatenated SQL literal into a placeholder query string. */
    static String sqlSuggestion(String code) {
        Matcher m = Pattern.compile("^(\\s*)(.*?=\\s*)\"(.*?)\"\\s*\\+.*$").matcher(code);
        if (!m.matches()) {
            return null;
        }
        String sql = m.group(3).replaceAll("'?\\s*$", "").trim();
        if (!sql.endsWith("?")) {
            sql = sql.replaceAll("\\s*=\\s*$", "") + " = ?";
        }
        return m.group(1) + m.group(2) + "\"" + sql + "\"; // bind the value with PreparedStatement#setX";
    }

    @Override
    public Mono<SummaryResult> summarize(SummaryContext ctx) {
        return Mono.fromSupplier(() -> new SummaryResult(templateSummary(ctx), null));
    }

    /** Deterministic summary used offline (and as every provider's last-resort fallback). */
    public static String templateSummary(SummaryContext ctx) {
        List<ReviewComment> comments = ctx.topComments();
        long critical = comments.stream().filter(c -> c.severity() == Severity.CRITICAL).count();
        long warning = comments.stream().filter(c -> c.severity() == Severity.WARNING).count();
        long suggestion = comments.stream().filter(c -> c.severity() == Severity.SUGGESTION).count();
        StringBuilder sb = new StringBuilder();
        sb.append("Reviewed ").append(ctx.files().size()).append(" file(s): ")
                .append(comments.size()).append(" finding(s) — ")
                .append(critical).append(" critical, ").append(warning).append(" warning, ")
                .append(suggestion).append(" suggestion.");
        Set<String> themes = new LinkedHashSet<>();
        for (ReviewComment c : comments) {
            if (c.severity() != Severity.SUGGESTION) {
                themes.add(theme(c.ruleKey()));
            }
        }
        themes.remove(null);
        if (!themes.isEmpty()) {
            sb.append(" Main concerns: ").append(String.join(", ", themes)).append('.');
        }
        if (ctx.risk() != null) {
            sb.append(" Risk ").append(ctx.risk().level()).append(" (").append(ctx.risk().score()).append("/100).");
        }
        return sb.toString();
    }

    private static String theme(String ruleKey) {
        if (ruleKey == null) {
            return null;
        }
        return switch (ruleKey) {
            case "local.hardcoded-secret", "local.live-api-key" -> "hardcoded credentials";
            case "local.sql-concat" -> "SQL injection";
            case "local.null-deref", "local.null-return-deref" -> "null safety";
            case "local.non-atomic" -> "thread safety";
            case "local.off-by-one" -> "boundary conditions";
            case "local.io-unhandled" -> "error handling";
            case "local.caller-arity", "local.removed-still-called" -> "callers broken by an API change";
            default -> ruleKey.startsWith("llm.") ? ruleKey.substring(4) : ruleKey;
        };
    }

    @Override
    public String providerId() {
        return "local";
    }

    @Override
    public String reviewModel() {
        return MODEL;
    }
}
