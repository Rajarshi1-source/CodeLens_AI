package com.codelensai.util;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal unified-diff parser. Splits a diff into per-file blocks and hunks, preserving the
 * real new-file start line from each {@code @@ -old,+new @@} header so comments anchor correctly.
 * Handles diffs with or without the leading {@code diff --git} line.
 */
@Component
public class DiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    public record Hunk(int newStartLine, String text) {
    }

    public record FileDiff(String path, String language, List<Hunk> hunks) {
    }

    public List<FileDiff> splitByFile(String unifiedDiff) {
        List<FileDiff> files = new ArrayList<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return files;
        }

        String[] lines = unifiedDiff.split("\n", -1);
        String currentPath = null;
        List<Hunk> hunks = new ArrayList<>();
        StringBuilder hunkBuf = null;
        int hunkStart = -1;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                hunkBuf = flushHunk(hunks, hunkBuf, hunkStart);
                hunkStart = -1;
                files = appendFile(files, currentPath, hunks);
                currentPath = parseGitPath(line);
                hunks = new ArrayList<>();
                continue;
            }
            if (line.startsWith("--- ")) {
                // New file block in the no-"diff --git" form (e.g. eval fixtures).
                if (currentPath == null || hunkBuf != null || !hunks.isEmpty()) {
                    hunkBuf = flushHunk(hunks, hunkBuf, hunkStart);
                    hunkStart = -1;
                    if (!hunks.isEmpty() || currentPath != null) {
                        files = appendFile(files, currentPath, hunks);
                        hunks = new ArrayList<>();
                    }
                }
                continue;
            }
            if (line.startsWith("+++ ")) {
                currentPath = stripPathPrefix(line.substring(4).trim());
                continue;
            }
            if (line.startsWith("index ") && hunkBuf == null) {
                continue;
            }

            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                hunkBuf = flushHunk(hunks, hunkBuf, hunkStart);
                hunkStart = Integer.parseInt(m.group(1));
                hunkBuf = new StringBuilder();
                hunkBuf.append(line).append('\n');
                continue;
            }
            if (hunkBuf != null) {
                hunkBuf.append(line).append('\n');
            }
        }
        flushHunk(hunks, hunkBuf, hunkStart);
        files = appendFile(files, currentPath, hunks);
        return files;
    }

    private StringBuilder flushHunk(List<Hunk> hunks, StringBuilder hunkBuf, int hunkStart) {
        if (hunkBuf != null && hunkStart >= 0 && !hunkBuf.isEmpty()) {
            hunks.add(new Hunk(hunkStart, hunkBuf.toString()));
        }
        return null;
    }

    private List<FileDiff> appendFile(List<FileDiff> files, String path, List<Hunk> hunks) {
        if (path != null && !hunks.isEmpty()) {
            files.add(new FileDiff(path, detectLanguage(path), List.copyOf(hunks)));
        }
        return files;
    }

    private String parseGitPath(String diffGitLine) {
        // "diff --git a/path b/path" -> take the b/ path
        String[] parts = diffGitLine.split("\\s+");
        if (parts.length >= 4) {
            return stripPathPrefix(parts[3]);
        }
        return null;
    }

    private String stripPathPrefix(String p) {
        if (p.startsWith("a/") || p.startsWith("b/")) {
            return p.substring(2);
        }
        return p;
    }

    public String detectLanguage(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "java" -> "java";
            case "py" -> "python";
            case "js", "jsx" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "go" -> "go";
            case "rb" -> "ruby";
            case "rs" -> "rust";
            case "kt" -> "kotlin";
            case "c", "h" -> "c";
            case "cpp", "cc", "hpp" -> "cpp";
            case "cs" -> "csharp";
            default -> "text";
        };
    }
}
