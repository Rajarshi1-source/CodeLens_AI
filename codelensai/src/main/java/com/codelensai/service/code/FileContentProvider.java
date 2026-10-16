package com.codelensai.service.code;

import java.util.Map;
import java.util.Optional;

/**
 * Supplies the full head-revision content of changed files, so structure-aware chunking can see the
 * function boundaries around a hunk. Absent content simply means hunk-level chunking for that file.
 */
@FunctionalInterface
public interface FileContentProvider {

    Optional<String> contentAt(String path);

    static FileContentProvider none() {
        return path -> Optional.empty();
    }

    /** Eval / demo: content supplied up front. */
    static FileContentProvider of(Map<String, String> files) {
        Map<String, String> copy = files == null ? Map.of() : Map.copyOf(files);
        return path -> Optional.ofNullable(copy.get(path));
    }
}
