package com.codelensai.service;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.util.DiffParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits a PR diff into LLM-reviewable {@link DiffChunk}s: by file first (independent review units),
 * then greedily by hunk under {@code maxTokens}. Generated/vendored files are skipped so we never
 * spend tokens reviewing noise. New-file line numbers are preserved for comment anchoring.
 */
@Service
public class DiffChunkerService {

    private static final Logger log = LoggerFactory.getLogger(DiffChunkerService.class);

    private static final Set<String> SKIP_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock", "poetry.lock");
    private static final Set<String> SKIP_DIR_SEGMENTS = Set.of(
            "node_modules", "dist", "build", "vendor", "target");
    private static final Set<String> SKIP_SUFFIXES = Set.of(
            ".min.js", ".map", ".pb.go", ".lock",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
            ".woff", ".woff2", ".ttf", ".zip", ".gz", ".jar");

    private final DiffParser diffParser;

    public DiffChunkerService(DiffParser diffParser) {
        this.diffParser = diffParser;
    }

    public List<DiffChunk> chunk(String unifiedDiff, int maxTokens) {
        List<DiffParser.FileDiff> files = diffParser.splitByFile(unifiedDiff);
        List<DiffChunk> chunks = new ArrayList<>();
        int skipped = 0;
        for (DiffParser.FileDiff file : files) {
            if (shouldSkip(file.path())) {
                skipped++;
                continue;
            }
            chunks.addAll(chunkFile(file, maxTokens));
        }
        if (skipped > 0) {
            log.info("Skipped {} generated/vendored file(s) during chunking", skipped);
        }
        return chunks;
    }

    private List<DiffChunk> chunkFile(DiffParser.FileDiff file, int maxTokens) {
        List<DiffChunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int chunkStartLine = -1;

        for (DiffParser.Hunk hunk : file.hunks()) {
            if (chunkStartLine < 0) {
                chunkStartLine = hunk.newStartLine();
            }
            if (buf.length() > 0
                    && estimateTokens(buf.length() + hunk.text().length()) > maxTokens) {
                out.add(new DiffChunk(file.path(), chunkStartLine, buf.toString(), file.language()));
                buf.setLength(0);
                chunkStartLine = hunk.newStartLine();
            }
            buf.append(hunk.text());
        }
        if (buf.length() > 0) {
            out.add(new DiffChunk(file.path(), chunkStartLine, buf.toString(), file.language()));
        }
        return out;
    }

    boolean shouldSkip(String path) {
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
