package com.codelensai.service;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.util.DiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffChunkerServiceTest {

    private final DiffChunkerService chunker = new DiffChunkerService(new DiffParser());

    @Test
    void skipsGeneratedAndVendoredFiles() {
        assertTrue(chunker.shouldSkip("package-lock.json"));
        assertTrue(chunker.shouldSkip("frontend/node_modules/left-pad/index.js"));
        assertTrue(chunker.shouldSkip("app.min.js"));
        assertTrue(chunker.shouldSkip("assets/logo.png"));
        assertFalse(chunker.shouldSkip("src/main/java/com/codelensai/Main.java"));
    }

    @Test
    void chunksByFileAndPreservesNewFileStartLine() {
        String diff = """
                diff --git a/src/Main.java b/src/Main.java
                index e69de29..abc1234 100644
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -40,3 +40,5 @@
                 public String displayName(Long userId) {
                +    User u = repo.findById(userId);
                +    return u.getName().toUpperCase();
                 }
                """;

        List<DiffChunk> chunks = chunker.chunk(diff, 4000);

        assertEquals(1, chunks.size());
        DiffChunk chunk = chunks.get(0);
        assertEquals("src/Main.java", chunk.fileName());
        assertEquals(40, chunk.startLine());
        assertEquals("java", chunk.language());
    }

    @Test
    void returnsNoChunksForEmptyDiff() {
        assertTrue(chunker.chunk("", 4000).isEmpty());
    }
}
