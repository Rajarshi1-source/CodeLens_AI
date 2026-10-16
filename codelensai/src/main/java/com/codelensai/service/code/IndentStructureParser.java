package com.codelensai.service.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic structure for indentation languages (Python, Ruby-ish): a {@code def}/{@code class} head
 * runs until the next non-blank line indented at or below the head's indentation.
 */
public class IndentStructureParser implements CodeStructureParser {

    private static final Pattern HEAD = Pattern.compile("^(\\s*)(?:async\\s+)?(def|class)\\s+([A-Za-z_]\\w*)");
    private static final Pattern CALL = Pattern.compile("(?<![\\w.])([A-Za-z_]\\w*)\\s*\\(|\\.([A-Za-z_]\\w*)\\s*\\(");
    private static final Set<String> NOT_NAMES = Set.of("if", "for", "while", "return", "print", "def", "class",
            "elif", "with", "assert", "not", "and", "or", "in", "lambda");

    @Override
    public boolean supports(String language) {
        return "python".equals(language) || "ruby".equals(language);
    }

    @Override
    public List<CodeUnit> parse(String path, String source) {
        List<CodeUnit> units = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return units;
        }
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEAD.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            int indent = m.group(1).replace("\t", "    ").length();
            int end = i;
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j];
                if (l.isBlank() || l.trim().startsWith("#")) {
                    continue;
                }
                int ind = l.length() - l.stripLeading().length();
                if (ind <= indent && !l.trim().startsWith(")")) {
                    break;
                }
                end = j;
            }
            String body = String.join("\n", java.util.Arrays.copyOfRange(lines, i, end + 1));
            boolean isClass = "class".equals(m.group(2));
            units.add(new CodeUnit(m.group(3), isClass ? CodeUnit.Kind.CLASS : CodeUnit.Kind.FUNCTION,
                    i + 1, end + 1, lines[i].trim(),
                    isClass ? Set.of() : calledNames(body, m.group(3)), Set.of(m.group(3))));
        }
        units.sort(Comparator.comparingInt(CodeUnit::startLine).thenComparing(u -> -u.length()));
        return units;
    }

    private static Set<String> calledNames(String body, String self) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = CALL.matcher(body);
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            if (!NOT_NAMES.contains(n) && !n.equals(self)) {
                names.add(n);
            }
        }
        return names;
    }
}
