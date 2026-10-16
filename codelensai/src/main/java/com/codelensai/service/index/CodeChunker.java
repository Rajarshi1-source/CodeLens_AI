package com.codelensai.service.index;

import com.codelensai.repository.CodeIndexRepository.IndexChunk;
import com.codelensai.service.code.CodeStructureParsers;
import com.codelensai.service.code.CodeUnit;
import com.codelensai.service.code.SymbolExtractor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a source file into index chunks along its structure: one chunk per method/function, plus a short
 * header chunk per class (its declaration and fields), or fixed windows when the language is not
 * understood. Each chunk records the names it declares (DEF) and calls (REF) — the symbol graph that lets
 * retrieval answer "who calls the function this PR changed?".
 */
@Component
public class CodeChunker {

    static final int MAX_CHUNK_LINES = 150;
    static final int CLASS_HEADER_LINES = 30;
    private static final Pattern CALL = Pattern.compile("(?<![\\w$])([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Set<String> NOT_CALLS = Set.of("if", "for", "while", "switch", "catch", "return", "new",
            "synchronized", "function", "def", "class", "print", "and", "or", "not");

    private final CodeStructureParsers parsers;

    public CodeChunker(CodeStructureParsers parsers) {
        this.parsers = parsers;
    }

    public List<IndexChunk> chunk(String path, String source) {
        List<IndexChunk> out = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return out;
        }
        String language = parsers.languageOf(path);
        String[] lines = source.split("\n", -1);
        List<CodeUnit> units = parsers.parseOrWindow(path, source);
        for (CodeUnit u : units) {
            switch (u.kind()) {
                case METHOD, FUNCTION -> out.add(toChunk(path, language, u, lines, u.startLine(), u.endLine(),
                        u.calledNames(), u.declaredNames()));
                case CLASS -> {
                    int end = Math.min(u.endLine(), u.startLine() + CLASS_HEADER_LINES - 1);
                    out.add(toChunk(path, language, u, lines, u.startLine(), end, Set.of(), u.declaredNames()));
                }
                case BLOCK, WINDOW -> {
                    String text = slice(lines, u.startLine(), u.endLine());
                    out.add(toChunk(path, language, u, lines, u.startLine(), u.endLine(),
                            calls(text), SymbolExtractor.declaredIn(text)));
                }
            }
        }
        return out;
    }

    private IndexChunk toChunk(String path, String language, CodeUnit u, String[] lines, int start, int end,
                               Set<String> refs, Set<String> defs) {
        int cappedEnd = Math.min(end, start + MAX_CHUNK_LINES - 1);
        String content = slice(lines, start, cappedEnd);
        Set<String> allDefs = new LinkedHashSet<>(defs);
        if (u.kind() != CodeUnit.Kind.WINDOW) {
            allDefs.add(u.name());
        }
        return new IndexChunk(path, language, u.kind() == CodeUnit.Kind.WINDOW ? null : u.name(), u.kind().name(),
                start, cappedEnd, content, sha256(u.kind() + "|" + u.name() + "|" + content), allDefs, refs);
    }

    static String slice(String[] lines, int startLine, int endLine) {
        int from = Math.max(0, startLine - 1);
        int to = Math.min(lines.length, endLine);
        return from >= to ? "" : String.join("\n", Arrays.copyOfRange(lines, from, to));
    }

    private static Set<String> calls(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CALL.matcher(text);
        while (m.find()) {
            if (!NOT_CALLS.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
