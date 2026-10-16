package com.codelensai.service.llm;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.enums.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic cross-file rules for the offline provider. They only fire when G1 retrieval supplied
 * caller snippets, which is exactly what makes the value of repo-aware context measurable in the eval
 * (run with and without retrieval): a diff-only reviewer structurally cannot see these bugs.
 *
 * <ul>
 *   <li>{@code local.caller-arity} — a function's parameter count changed but a caller still passes the old count.</li>
 *   <li>{@code local.removed-still-called} — a function was removed/renamed but a caller still calls it.</li>
 *   <li>{@code local.null-return-deref} — a function newly returns null and a caller dereferences its result.</li>
 * </ul>
 */
final class LocalContextRules {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    /** Java/C#/TS-style method declarations, plus {@code def} (Python) and {@code function} (JS). */
    private static final List<Pattern> DECLARATIONS = List.of(
            Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|synchronized|abstract|default|async|override|export)\\s+)*"
                    + "[\\w<>\\[\\],.?]+(?:\\s*<[^>]*>)?\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)\\s*(?:throws[^{]*)?\\{?\\s*$"),
            Pattern.compile("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)"),
            Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)"));
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "return", "new",
            "else", "synchronized", "try", "do");

    private record Signature(String name, int arity, int line) {
    }

    private LocalContextRules() {
    }

    static List<ReviewEvent.Finding> evaluate(ReviewPromptContext ctx) {
        List<ReviewEvent.Finding> out = new ArrayList<>();
        DiffChunk chunk = ctx.chunk();
        if (chunk == null || chunk.content() == null || ctx.callers().isEmpty()) {
            return out;
        }
        Map<String, Signature> added = new LinkedHashMap<>();
        Map<String, Signature> removed = new LinkedHashMap<>();
        List<int[]> nullReturns = new ArrayList<>();        // {line}
        Map<Integer, String> enclosingAt = new HashMap<>();  // new-file line -> enclosing method name

        int line = chunk.startLine() > 0 ? chunk.startLine() : 1;
        String enclosing = null;
        for (String raw : chunk.content().split("\n", -1)) {
            Matcher h = HUNK_HEADER.matcher(raw);
            if (h.matches()) {
                line = Integer.parseInt(h.group(1));
                continue;
            }
            if (raw.startsWith("+++") || raw.startsWith("---")) {
                continue;
            }
            char kind = raw.isEmpty() ? ' ' : raw.charAt(0);
            String code = raw.isEmpty() ? "" : raw.substring(1);
            Signature sig = declaration(code, line);
            if (kind == '-') {
                if (sig != null) {
                    removed.put(sig.name(), sig);
                }
                continue;
            }
            if (sig != null) {
                enclosing = sig.name();
                if (kind == '+') {
                    added.put(sig.name(), sig);
                }
            }
            if (kind == '+' && code.trim().matches("return\\s+null\\s*;")) {
                nullReturns.add(new int[]{line});
                enclosingAt.put(line, enclosing);
            }
            line++;
        }

        for (String caller : ctx.callers()) {
            String path = snippetPath(caller);
            String body = snippetBody(caller);
            for (Signature sig : added.values()) {
                for (int args : callArities(body, sig.name())) {
                    if (args != sig.arity()) {
                        out.add(finding(chunk, sig.line(), Severity.CRITICAL, 0.85, "local.caller-arity",
                                "`" + sig.name() + "` now takes " + sig.arity() + " parameter(s), but the caller in "
                                        + path + " still passes " + args + " argument(s). Update every caller "
                                        + "(or keep an overload) before merging — this breaks at compile/run time."));
                        break;
                    }
                }
            }
            for (Signature gone : removed.values()) {
                if (added.containsKey(gone.name())) {
                    continue;
                }
                if (!callArities(body, gone.name()).isEmpty()) {
                    out.add(finding(chunk, Math.max(1, gone.line()), Severity.CRITICAL, 0.8, "local.removed-still-called",
                            "`" + gone.name() + "` was removed or renamed here, but " + path
                                    + " still calls it. Update that caller or keep a delegating method."));
                }
            }
            for (int[] nr : nullReturns) {
                String method = enclosingAt.get(nr[0]);
                if (method != null && dereferencesResult(body, method)) {
                    out.add(finding(chunk, nr[0], Severity.WARNING, 0.7, "local.null-return-deref",
                            "`" + method + "` can now return null, but the caller in " + path
                                    + " dereferences its result without a null check. Return Optional or guard the call site."));
                }
            }
        }
        return dedupe(out);
    }

    private static ReviewEvent.Finding finding(DiffChunk chunk, int line, Severity severity, double confidence,
                                               String rule, String text) {
        return new ReviewEvent.Finding(chunk.fileName(), line, severity, text, null, confidence, rule);
    }

    private static List<ReviewEvent.Finding> dedupe(List<ReviewEvent.Finding> in) {
        Map<String, ReviewEvent.Finding> byKey = new LinkedHashMap<>();
        for (ReviewEvent.Finding f : in) {
            byKey.putIfAbsent(f.file() + "|" + f.line() + "|" + f.ruleKey(), f);
        }
        return new ArrayList<>(byKey.values());
    }

    static Signature declaration(String code, int line) {
        for (Pattern p : DECLARATIONS) {
            Matcher m = p.matcher(code);
            if (m.find() && !KEYWORDS.contains(m.group(1))) {
                return new Signature(m.group(1), arity(m.group(2), p != DECLARATIONS.get(0)), line);
            }
        }
        return null;
    }

    private static int arity(String params, boolean dropSelf) {
        if (params == null || params.isBlank()) {
            return 0;
        }
        List<String> parts = splitTopLevel(params);
        if (dropSelf && !parts.isEmpty() && (parts.get(0).trim().equals("self") || parts.get(0).trim().equals("cls"))) {
            parts = parts.subList(1, parts.size());
        }
        return (int) parts.stream().filter(p -> !p.isBlank()).count();
    }

    /** Argument counts of every call {@code name(...)} in {@code body}, skipping declarations of it. */
    static List<Integer> callArities(String body, String name) {
        List<Integer> out = new ArrayList<>();
        Pattern call = Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "\\s*\\(");
        for (String ln : body.split("\n")) {
            if (declaration(ln, 0) instanceof Signature s && s.name().equals(name)) {
                continue;
            }
            Matcher m = call.matcher(ln);
            while (m.find()) {
                int open = m.end() - 1;
                String inside = balanced(ln, open);
                if (inside != null) {
                    out.add(inside.isBlank() ? 0 : splitTopLevel(inside).size());
                }
            }
        }
        return out;
    }

    private static boolean dereferencesResult(String body, String method) {
        Pattern direct = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\([^;]*?\\)\\s*\\.\\s*\\w+");
        if (direct.matcher(body).find()) {
            return true;
        }
        Matcher assign = Pattern.compile("(\\w+)\\s*=\\s*(?:[\\w.]+\\.)?" + Pattern.quote(method) + "\\s*\\(").matcher(body);
        while (assign.find()) {
            String var = assign.group(1);
            String rest = body.substring(assign.end());
            boolean guarded = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*[!=]=\\s*null").matcher(rest).find();
            if (!guarded && Pattern.compile("\\b" + Pattern.quote(var) + "\\s*\\.\\s*\\w+").matcher(rest).find()) {
                return true;
            }
        }
        return false;
    }

    private static String balanced(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return s.substring(openIdx + 1, i);
                }
            }
        }
        return null;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == '<' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']' || c == '}') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            parts.add(cur.toString());
        }
        return parts;
    }

    /** Snippets are formatted {@code // path:start-end (symbol)\n<code>} by the retrieval service. */
    private static String snippetPath(String snippet) {
        int nl = snippet.indexOf('\n');
        String head = nl < 0 ? snippet : snippet.substring(0, nl);
        if (head.startsWith("// ")) {
            String rest = head.substring(3);
            int colon = rest.indexOf(':');
            return colon > 0 ? rest.substring(0, colon) : rest;
        }
        return "a caller";
    }

    private static String snippetBody(String snippet) {
        return snippet.startsWith("// ") && snippet.indexOf('\n') > 0 ? snippet.substring(snippet.indexOf('\n') + 1) : snippet;
    }
}
