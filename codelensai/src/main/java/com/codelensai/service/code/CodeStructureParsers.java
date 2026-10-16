package com.codelensai.service.code;

import com.codelensai.util.DiffParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Picks the {@link CodeStructureParser} for a file's language (JavaParser for Java, brace/indent
 * heuristics for the rest) and falls back to fixed overlapping windows for anything else, so every file
 * still gets indexed.
 */
@Component
public class CodeStructureParsers {

    static final int WINDOW = 80;
    static final int OVERLAP = 10;

    private final List<CodeStructureParser> parsers = List.of(
            new JavaParserStructureParser(), new IndentStructureParser(), new BraceStructureParser());
    private final DiffParser diffParser;

    public CodeStructureParsers(DiffParser diffParser) {
        this.diffParser = diffParser;
    }

    public String languageOf(String path) {
        return diffParser.detectLanguage(path);
    }

    public List<CodeUnit> parse(String path, String source) {
        String language = languageOf(path);
        for (CodeStructureParser p : parsers) {
            if (p.supports(language)) {
                return p.parse(path, source);
            }
        }
        return List.of();
    }

    /** Units, or fixed windows when the language yields none. */
    public List<CodeUnit> parseOrWindow(String path, String source) {
        List<CodeUnit> units = parse(path, source);
        return units.isEmpty() ? windows(source) : units;
    }

    /** The innermost callable (else innermost unit) containing {@code line}. */
    public static Optional<CodeUnit> enclosing(List<CodeUnit> units, int line) {
        CodeUnit best = null;
        for (CodeUnit u : units) {
            if (u.contains(line) && (best == null || u.length() < best.length())) {
                best = u;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The outermost unit that is a callable (so a whole method stays in one chunk), else the innermost unit. */
    public static Optional<CodeUnit> groupingUnit(List<CodeUnit> units, int line) {
        CodeUnit outerCallable = null;
        for (CodeUnit u : units) {
            if (u.isCallable() && u.contains(line) && (outerCallable == null || u.length() > outerCallable.length())) {
                outerCallable = u;
            }
        }
        return outerCallable != null ? Optional.of(outerCallable) : enclosing(units, line);
    }

    static List<CodeUnit> windows(String source) {
        List<CodeUnit> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        int total = source.split("\n", -1).length;
        for (int start = 1; start <= total; start += WINDOW - OVERLAP) {
            int end = Math.min(total, start + WINDOW - 1);
            out.add(new CodeUnit("lines " + start + "-" + end, CodeUnit.Kind.WINDOW, start, end, null, null, null));
            if (end == total) {
                break;
            }
        }
        return out;
    }
}
