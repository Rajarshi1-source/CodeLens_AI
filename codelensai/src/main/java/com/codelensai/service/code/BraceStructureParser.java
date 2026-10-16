package com.codelensai.service.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic structure for brace languages (JS/TS, Go, C#, Kotlin, Rust, C/C++, and Java snippets that do
 * not parse): find a function/class head with a regex, then match braces to its end while ignoring
 * braces inside strings and comments. Good enough to keep a function in one chunk; not a compiler.
 */
public class BraceStructureParser implements CodeStructureParser {

    private static final Set<String> LANGUAGES = Set.of(
            "javascript", "typescript", "go", "csharp", "kotlin", "rust", "c", "cpp", "java");

    private static final List<HeadPattern> HEADS = List.of(
            new HeadPattern(Pattern.compile("^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?(?:class|interface|struct|enum|record|object|trait|impl)\\s+([A-Za-z_]\\w*)"),
                    CodeUnit.Kind.CLASS),
            new HeadPattern(Pattern.compile("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*\\("),
                    CodeUnit.Kind.FUNCTION),
            new HeadPattern(Pattern.compile("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*(?:async\\s+)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=>"),
                    CodeUnit.Kind.FUNCTION),
            new HeadPattern(Pattern.compile("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)\\s*\\("), CodeUnit.Kind.FUNCTION),
            new HeadPattern(Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?fn\\s+([A-Za-z_]\\w*)"), CodeUnit.Kind.FUNCTION),
            new HeadPattern(Pattern.compile("^\\s*(?:fun|suspend\\s+fun)\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?([A-Za-z_]\\w*)\\s*\\("), CodeUnit.Kind.FUNCTION),
            new HeadPattern(Pattern.compile("^\\s*(?:(?:public|private|protected|internal|static|final|virtual|override|abstract|async|synchronized|readonly|unsafe)\\s+)*"
                    + "(?!return\\b|new\\b|else\\b|throw\\b)[A-Za-z_][\\w<>\\[\\],.?]*(?:\\s*<[^>]*>)?\\s+([A-Za-z_]\\w*)\\s*\\([^;]*\\)\\s*(?:throws\\s+[^{]+)?\\{?\\s*$"),
                    CodeUnit.Kind.METHOD),
            new HeadPattern(Pattern.compile("^\\s*(?:(?:public|private|protected|static|async|get|set)\\s+)*([A-Za-z_$][\\w$]*)\\s*\\([^;]*\\)\\s*(?::\\s*[^={]+)?\\{\\s*$"),
                    CodeUnit.Kind.METHOD));

    private static final Set<String> NOT_NAMES = Set.of("if", "for", "while", "switch", "catch", "return", "else",
            "do", "try", "function", "synchronized", "using", "lock", "foreach", "with");
    private static final Pattern CALL = Pattern.compile("(?<![\\w$])([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern DECLARED = Pattern.compile("(?:const|let|var|val)\\s+([A-Za-z_$][\\w$]*)");

    private record HeadPattern(Pattern pattern, CodeUnit.Kind kind) {
    }

    @Override
    public boolean supports(String language) {
        return LANGUAGES.contains(language);
    }

    @Override
    public List<CodeUnit> parse(String path, String source) {
        List<CodeUnit> units = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return units;
        }
        String[] lines = source.split("\n", -1);
        String[] stripped = stripStringsAndComments(lines);
        for (int i = 0; i < lines.length; i++) {
            for (HeadPattern head : HEADS) {
                Matcher m = head.pattern().matcher(lines[i]);
                if (!m.find() || NOT_NAMES.contains(m.group(1))) {
                    continue;
                }
                int end = matchBraces(stripped, i);
                if (end < 0) {
                    break;
                }
                String body = String.join("\n", java.util.Arrays.copyOfRange(stripped, i, end + 1));
                units.add(new CodeUnit(m.group(1), head.kind(), i + 1, end + 1, lines[i].trim(),
                        head.kind() == CodeUnit.Kind.CLASS ? Set.of() : calledNames(body, m.group(1)),
                        declaredNames(body, m.group(1))));
                break;
            }
        }
        units.sort(Comparator.comparingInt(CodeUnit::startLine).thenComparing(u -> -u.length()));
        return units;
    }

    /** Line index of the brace closing the block opened at/after {@code start}; -1 if none opens within 3 lines. */
    static int matchBraces(String[] stripped, int start) {
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < stripped.length; i++) {
            for (char c : stripped[i].toCharArray()) {
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        return i;
                    }
                } else if (c == ';' && !opened && i == start) {
                    return -1; // a declaration/prototype, not a body
                }
            }
            if (!opened && i - start >= 3) {
                return -1;
            }
        }
        return -1;
    }

    /** Blank out string/char literals and comments so braces inside them are not counted. */
    static String[] stripStringsAndComments(String[] lines) {
        String[] out = new String[lines.length];
        boolean inBlock = false;
        for (int li = 0; li < lines.length; li++) {
            String l = lines[li];
            StringBuilder sb = new StringBuilder(l.length());
            char quote = 0;
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                char next = i + 1 < l.length() ? l.charAt(i + 1) : 0;
                if (inBlock) {
                    if (c == '*' && next == '/') {
                        inBlock = false;
                        i++;
                    }
                    sb.append(' ');
                } else if (quote != 0) {
                    if (c == '\\') {
                        i++;
                        sb.append(' ');
                    } else if (c == quote) {
                        quote = 0;
                    }
                    sb.append(' ');
                } else if (c == '/' && next == '/') {
                    break;
                } else if (c == '/' && next == '*') {
                    inBlock = true;
                    i++;
                    sb.append(' ');
                } else if (c == '"' || c == '\'' || c == '`') {
                    quote = c;
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
            out[li] = sb.toString();
        }
        return out;
    }

    private static Set<String> calledNames(String body, String self) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = CALL.matcher(body);
        while (m.find()) {
            String n = m.group(1);
            if (!NOT_NAMES.contains(n) && !n.equals(self)) {
                names.add(n);
            }
        }
        return names;
    }

    private static Set<String> declaredNames(String body, String self) {
        Set<String> names = new LinkedHashSet<>();
        names.add(self);
        Matcher m = DECLARED.matcher(body);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
