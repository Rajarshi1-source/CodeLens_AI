package com.codelensai.service.code;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls declared function/class names out of individual lines (diff lines, or the funcname context git
 * appends to {@code @@} hunk headers). Used to key G1 caller/test retrieval when the full file is not
 * available, so retrieval still works for hunk-only diffs.
 */
public final class SymbolExtractor {

    private static final List<Pattern> DECLARATIONS = List.of(
            Pattern.compile("(?:class|interface|record|enum|struct|trait)\\s+([A-Za-z_]\\w*)"),
            Pattern.compile("(?:def|function|func|fn|fun)\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_$][\\w$]*)\\s*[(<]"),
            Pattern.compile("^\\s*(?:(?:public|private|protected|internal|static|final|abstract|async|synchronized|override|virtual|export|default)\\s+)*"
                    + "[A-Za-z_][\\w<>\\[\\],.?]*(?:\\s*<[^>]*>)?\\s+([A-Za-z_]\\w*)\\s*\\("),
            Pattern.compile("(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\("));
    private static final Set<String> NOT_NAMES = Set.of("if", "for", "while", "switch", "catch", "return", "new",
            "else", "throw", "await", "yield", "do", "try");
    private static final Pattern HUNK_FUNCNAME = Pattern.compile("^@@ [^@]+ @@\\s*(.*)$");

    private SymbolExtractor() {
    }

    public static Set<String> declaredIn(String line) {
        Set<String> out = new LinkedHashSet<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        for (Pattern p : DECLARATIONS) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                String name = m.group(1);
                if (!NOT_NAMES.contains(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** Declared names in a hunk: its {@code @@} funcname context plus every +/-/context line. */
    public static Set<String> declaredInHunk(String hunkText) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : hunkText.split("\n")) {
            Matcher h = HUNK_FUNCNAME.matcher(raw);
            if (h.matches()) {
                out.addAll(declaredIn(h.group(1)));
                continue;
            }
            if (raw.startsWith("+++") || raw.startsWith("---")) {
                continue;
            }
            out.addAll(declaredIn(raw.isEmpty() ? raw : raw.substring(1)));
        }
        return out;
    }
}
