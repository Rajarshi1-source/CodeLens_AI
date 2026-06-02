# CodeLens AI — Diff Chunking Reference

How `DiffChunkerService` splits a PR diff into LLM-reviewable segments. Load when implementing or tuning chunking, adding AST-aware chunking (G4 / tree-sitter), or debugging context-window overflows and split functions. The goal: never split a logical unit across calls, never blow the model's context, never review noise (generated/vendored files).

## Contents
1. Why chunking exists
2. The chunking contract
3. Level 1 — split by file
4. Level 2 — split by hunk / size
5. Level 3 — AST-aware (tree-sitter, G4)
6. Files to skip
7. Token estimation
8. Line-number fidelity (critical)

## 1. Why chunking exists

A large PR diff can exceed the model's usable context, and even when it fits, dumping everything degrades review quality. Chunking sends focused, coherent segments — ideally one logical unit (a function/class) plus enough surrounding context to reason about it — so each LLM call is cheap, parallelizable (one virtual thread per chunk), and high-signal.

## 2. The chunking contract

A chunk is a `DiffChunk(fileName, startLine, content, language)` where:
- `content` is small enough to fit comfortably under `MAX_TOKENS_PER_CHUNK` (~4000 tokens) including the prompt scaffold.
- `startLine` maps to the **real line number in the new file**, so comments anchor correctly (see §8).
- A single function/class is never split across two chunks (best effort; hard cap wins if a function is enormous).
- `language` is detected from the file extension and passed as a prompt hint.

## 3. Level 1 — split by file

Always split on file boundaries first. Different files are independent review units and mixing them confuses anchoring.

```java
public List<DiffChunk> chunk(String unifiedDiff, int maxTokens) {
    List<FileDiff> files = diffParser.splitByFile(unifiedDiff);   // parse "diff --git" blocks
    List<DiffChunk> chunks = new ArrayList<>();
    for (FileDiff file : files) {
        if (shouldSkip(file)) continue;                            // §6
        chunks.addAll(chunkFile(file, maxTokens));
    }
    return chunks;
}
```

## 4. Level 2 — split by hunk / size

Within a file, group hunks (`@@ ... @@` sections) greedily until adding the next hunk would exceed `maxTokens`; then start a new chunk. Carry a few lines of leading context so the model sees the surrounding code.

```java
private List<DiffChunk> chunkFile(FileDiff file, int maxTokens) {
    List<DiffChunk> out = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    int chunkStartLine = -1;

    for (Hunk hunk : file.hunks()) {
        if (chunkStartLine < 0) chunkStartLine = hunk.newStartLine();
        if (estimateTokens(buf.length() + hunk.text().length()) > maxTokens && buf.length() > 0) {
            out.add(new DiffChunk(file.path(), chunkStartLine, buf.toString(), file.language()));
            buf.setLength(0);
            chunkStartLine = hunk.newStartLine();
        }
        buf.append(hunk.text());
    }
    if (buf.length() > 0)
        out.add(new DiffChunk(file.path(), chunkStartLine, buf.toString(), file.language()));
    return out;
}
```

## 5. Level 3 — AST-aware (tree-sitter, G4 differentiator)

Hunk-based splitting can still cut a function in half if a single hunk is huge. AST-aware chunking parses the changed file with tree-sitter, finds the enclosing function/class node for each changed range, and emits one chunk per logical unit — so a function is always whole.

Approach:
1. Run tree-sitter for the file's `language` (grammars per language give multi-language support).
2. For each changed line range, walk up to the nearest function/method/class node.
3. Emit that node's full span as the chunk (deduplicate when several changes share an enclosing node).
4. If a single node still exceeds `maxTokens`, fall back to Level 2 within that node.

This is a stretch goal — Level 1+2 is a solid MVP. The interview point: "a function is never split across LLM calls, so the model always sees complete logic."

## 6. Files to skip

Reviewing generated or vendored files wastes tokens and produces noise. Skip:
- Lockfiles: `package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`, `Cargo.lock`, `poetry.lock`.
- Vendored/build dirs: `node_modules/`, `dist/`, `build/`, `vendor/`, `target/`.
- Generated/minified: `*.min.js`, `*.map`, `*.pb.go`, `*_generated.*`, `*.lock`.
- Binary/asset extensions: images, fonts, archives.

When skipping, record a notice so the UI can show "N files skipped (generated/vendored)" rather than silently dropping them. For very large diffs, cap the number of files reviewed and surface the cap.

## 7. Token estimation

A cheap heuristic is fine for chunk sizing: ~4 characters per token for code. Be conservative (estimate high) so a chunk plus the prompt scaffold never overflows. Validate against the provider's tokenizer in tests for a few representative diffs.

```java
private int estimateTokens(int chars) { return (chars / 4) + 64; }   // +scaffold headroom
```

## 8. Line-number fidelity (critical)

Comments anchor to `(file, line)` in the diff viewer. The chunk's `startLine` and each comment's reported line must map to **actual new-file line numbers from the hunk headers** (`@@ -old,+new @@`). After the model returns comments, validate every `(file, line)` against the real hunk ranges and **drop hallucinated lines** — a comment on a line that isn't in the diff is worse than no comment. This validation lives in the review pipeline (see the Spring skill's mitigation section); chunking's job is to preserve correct line numbers so the validation has something true to check against.
