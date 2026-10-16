package com.codelensai.service;

import com.codelensai.model.dto.DiffChunk;
import com.codelensai.service.code.FileContentProvider;
import com.codelensai.util.DiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        assertTrue(chunk.symbols().contains("displayName"));
    }

    @Test
    void returnsNoChunksForEmptyDiff() {
        assertTrue(chunker.chunk("", 4000).isEmpty());
    }

    /**
     * other() then a long process() with two far-apart hunks. Greedy hunk packing (no content) fills a chunk
     * with other()+first process() hunk and spills the second process() hunk into another chunk — splitting
     * the method. With the file content (G4) the method's hunks are grouped and never split.
     */
    @Test
    void hunksOfTheSameMethodStayInOneChunk() {
        StringBuilder src = new StringBuilder(
                "public class Big {\n    public void other() {\n        x();\n    }\n\n    public void process() {\n");
        for (int i = 0; i < 60; i++) {
            src.append("        step").append(i).append("();\n");
        }
        src.append("    }\n}\n");
        String diff = """
                --- a/Big.java
                +++ b/Big.java
                @@ -2,2 +2,3 @@ public void other() {
                     public void other() {
                +        y();
                         x();
                @@ -8,3 +9,4 @@ public void process() {
                         step1();
                +        audit(1);
                         step2();
                @@ -59,3 +61,4 @@ public void process() {
                         step52();
                +        audit(2);
                         step53();
                """;
        int budget = 120; // one hunk ~87 tokens, two ~111, three ~134
        List<DiffChunk> greedy = chunker.chunk(diff, budget);
        assertEquals(2, greedy.size());
        assertFalse(greedy.get(0).content().contains("audit(2)") && greedy.get(0).content().contains("audit(1)"),
                "greedy packing splits process() across chunks");

        List<DiffChunk> structured = chunker.chunk(diff, budget, FileContentProvider.of(Map.of("Big.java", src.toString())));
        assertEquals(2, structured.size());
        DiffChunk process = structured.get(1);
        assertTrue(process.content().contains("audit(1)") && process.content().contains("audit(2)"),
                "both process() hunks reviewed together");
        assertTrue(process.symbols().contains("process"));
        assertEquals(1, chunker.chunk(diff, 4000, FileContentProvider.of(Map.of("Big.java", src.toString()))).size());
    }
}
