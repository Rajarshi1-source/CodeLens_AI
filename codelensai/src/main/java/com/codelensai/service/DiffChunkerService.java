package com.codelensai.service;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.service.code.CodeStructureParsers;
import com.codelensai.service.code.CodeUnit;
import com.codelensai.service.code.FileContentProvider;
import com.codelensai.service.code.SymbolExtractor;
import com.codelensai.util.DiffParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a PR diff into LLM-reviewable {@link DiffChunk}s: by file first (independent review units), then
 * packed under {@code maxTokens}. Generated/vendored files are skipped so we never spend tokens on noise.
 * New-file line numbers are preserved for comment anchoring.
 *
 * <p><b>Structure-aware (G4).</b> When the head content of a file is available, hunks are grouped by the
 * function/method that encloses their changed lines, and a group is never split across chunks — so the
 * model always sees a whole edited function together. A group that alone exceeds the budget falls back to
 * hunk-level packing (counted in {@code chunker.oversize_units}). Without content the chunker packs hunks
 * greedily, as before. Each chunk carries the {@code symbols} it touches, the keys for G1 retrieval.
 */
@Service
public class DiffChunkerService {

    private static final Logger log = LoggerFactory.getLogger(DiffChunkerService.class);
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private static final Set<String> SKIP_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock", "poetry.lock");
    private static final Set<String> SKIP_DIR_SEGMENTS = Set.of(
            "node_modules", "dist", "build", "vendor", "target");
    private static final Set<String> SKIP_SUFFIXES = Set.of(
            ".min.js", ".map", ".pb.go", ".lock",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
            ".woff", ".woff2", ".ttf", ".zip", ".gz", ".jar");

    private final DiffParser diffParser;
    private final CodeStructureParsers parsers;
    private final MeterRegistry meters;

    @Autowired
    public DiffChunkerService(DiffParser diffParser, CodeStructureParsers parsers, MeterRegistry meters) {
        this.diffParser = diffParser;
        this.parsers = parsers;
        this.meters = meters;
    }

    /** Convenience for tests / callers without Spring. */
    public DiffChunkerService(DiffParser diffParser) {
        this(diffParser, new CodeStructureParsers(diffParser), new SimpleMeterRegistry());
    }

    public List<DiffChunk> chunk(String unifiedDiff, int maxTokens) {
        return chunk(unifiedDiff, maxTokens, FileContentProvider.none());
    }

    public List<DiffChunk> chunk(String unifiedDiff, int maxTokens, FileContentProvider contents) {
        List<DiffParser.FileDiff> files = diffParser.splitByFile(unifiedDiff);
        List<DiffChunk> chunks = new ArrayList<>();
        int skipped = 0;
        for (DiffParser.FileDiff file : files) {
            if (shouldSkip(file.path())) {
                skipped++;
                continue;
            }
            Optional<String> content = contents == null ? Optional.empty() : contents.contentAt(file.path());
            List<CodeUnit> units = content.map(c -> parsers.parse(file.path(), c)).orElse(List.of());
            chunks.addAll(units.isEmpty() ? chunkByHunk(file, file.hunks(), maxTokens)
                    : chunkByStructure(file, units, maxTokens));
        }
        if (skipped > 0) {
            log.info("Skipped {} generated/vendored file(s) during chunking", skipped);
        }
        return chunks;
    }

    /** Greedy hunk packing (no structure available). */
    private List<DiffChunk> chunkByHunk(DiffParser.FileDiff file, List<DiffParser.Hunk> hunks, int maxTokens) {
        List<DiffChunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Set<String> symbols = new LinkedHashSet<>();
        int chunkStartLine = -1;
        for (DiffParser.Hunk hunk : hunks) {
            if (chunkStartLine < 0) {
                chunkStartLine = hunk.newStartLine();
            }
            if (!buf.isEmpty() && estimateTokens(buf.length() + hunk.text().length()) > maxTokens) {
                out.add(new DiffChunk(file.path(), chunkStartLine, buf.toString(), file.language(), List.copyOf(symbols)));
                buf.setLength(0);
                symbols.clear();
                chunkStartLine = hunk.newStartLine();
            }
            buf.append(hunk.text());
            symbols.addAll(SymbolExtractor.declaredInHunk(hunk.text()));
        }
        if (!buf.isEmpty()) {
            out.add(new DiffChunk(file.path(), chunkStartLine, buf.toString(), file.language(), List.copyOf(symbols)));
        }
        return out;
    }

    /** Group hunks by their enclosing function, then pack whole groups. */
    private List<DiffChunk> chunkByStructure(DiffParser.FileDiff file, List<CodeUnit> units, int maxTokens) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (DiffParser.Hunk hunk : file.hunks()) {
            int anchor = firstChangedLine(hunk);
            Optional<CodeUnit> unit = CodeStructureParsers.groupingUnit(units, anchor);
            String key = unit.map(u -> u.startLine() + ":" + u.endLine()).orElse("hunk@" + hunk.newStartLine());
            Group g = groups.computeIfAbsent(key, k -> new Group());
            g.hunks.add(hunk);
            unit.ifPresent(u -> {
                if (u.kind() != CodeUnit.Kind.WINDOW) {
                    g.symbols.add(u.name());
                }
            });
            g.symbols.addAll(SymbolExtractor.declaredInHunk(hunk.text()));
        }

        List<DiffChunk> out = new ArrayList<>();
        List<DiffParser.Hunk> pending = new ArrayList<>();
        Set<String> pendingSymbols = new LinkedHashSet<>();
        int pendingChars = 0;
        for (Group g : groups.values()) {
            int groupChars = g.hunks.stream().mapToInt(h -> h.text().length()).sum();
            if (estimateTokens(groupChars) > maxTokens) {
                flush(out, file, pending, pendingSymbols);
                pendingChars = 0;
                meters.counter("chunker.oversize_units").increment();
                log.debug("Unit in {} exceeds {} tokens; falling back to hunk-level packing", file.path(), maxTokens);
                out.addAll(chunkByHunk(file, g.hunks, maxTokens));
                continue;
            }
            if (!pending.isEmpty() && estimateTokens(pendingChars + groupChars) > maxTokens) {
                flush(out, file, pending, pendingSymbols);
                pendingChars = 0;
            }
            pending.addAll(g.hunks);
            pendingSymbols.addAll(g.symbols);
            pendingChars += groupChars;
        }
        flush(out, file, pending, pendingSymbols);
        return out;
    }

    private static void flush(List<DiffChunk> out, DiffParser.FileDiff file, List<DiffParser.Hunk> hunks, Set<String> symbols) {
        if (hunks.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        hunks.forEach(h -> sb.append(h.text()));
        out.add(new DiffChunk(file.path(), hunks.get(0).newStartLine(), sb.toString(), file.language(), List.copyOf(symbols)));
        hunks.clear();
        symbols.clear();
    }

    /** New-file line of the first added line (or the deletion point for a pure deletion). */
    static int firstChangedLine(DiffParser.Hunk hunk) {
        int line = hunk.newStartLine();
        for (String raw : hunk.text().split("\n", -1)) {
            Matcher m = HUNK_HEADER.matcher(raw);
            if (m.matches()) {
                line = Integer.parseInt(m.group(1));
                continue;
            }
            if (raw.startsWith("+") || raw.startsWith("-")) {
                return line;
            }
            line++;
        }
        return hunk.newStartLine();
    }

    private static final class Group {
        final List<DiffParser.Hunk> hunks = new ArrayList<>();
        final Set<String> symbols = new LinkedHashSet<>();
    }

    public boolean shouldSkip(String path) {
        if (path == null) {
            return true;
        }
        String lower = path.toLowerCase();
        String name = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        if (SKIP_NAMES.contains(name)) {
            return true;
        }
        for (String suffix : SKIP_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        for (String segment : lower.split("/")) {
            if (SKIP_DIR_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return lower.contains("_generated.") || lower.contains(".generated.");
    }

    /** Conservative heuristic: ~4 chars per token of code, plus prompt-scaffold headroom. */
    int estimateTokens(int chars) {
        return (chars / 4) + 64;
    }
}
