# ADR 0005 — Repository-aware review context: pgvector index + pluggable embeddings (G1)

- Status: Accepted
- Date: 2026-10

## Context

A diff-only reviewer cannot see the most expensive class of bugs: a changed signature whose callers still
pass the old arguments, a removed method that is still called, a new `return null` that a caller
dereferences. Those require the code *around* the diff — callers, tests, similar code — from the
repository's default branch. We already run Postgres; the question was where to keep the index and how
to embed code without making an API key mandatory.

## Decision

- **Index in Postgres with pgvector** (`code_chunks.embedding vector(512)`, HNSW `vector_cosine_ops`),
  plus `code_symbols(chunk_id, symbol, role DEF|REF)` for exact caller lookup. Accessed through
  `JdbcClient` with `CAST(:v AS vector)` — no Hibernate vector dependency. `schema.sql` stays idempotent
  (`CREATE EXTENSION IF NOT EXISTS vector`, `ADD COLUMN IF NOT EXISTS`, no `DO $$` blocks).
- **Chunk along code structure** (G4): a sealed `CodeStructureParser` — JavaParser for Java, brace and
  indent heuristics for JS/TS/Go/C#/Kotlin/Rust/Python, 80-line windows as the last resort. Diff chunking
  uses the same parsers so a function is never split across two LLM calls. Tree-sitter is future work.
- **Pluggable embeddings**, sealed `EmbeddingProvider`: the default is an offline feature-hashing
  embedder (`hashing-v1`: identifier tokenization, FNV-1a signed hashing, log-tf, L2 norm) so dev, CI,
  the eval and the demo need no key; `text-embedding-3-small` with `dimensions=512` is opt-in. Startup
  fails fast if the configured dimension is not 512.
- **Ranking**: exact callers of the changed symbols first, then their tests, then vector neighbours;
  changed paths are excluded; snippets capped at 40 lines and the total at
  `codelens.retrieval.max-context-tokens` per chunk. Retrieved code is marked *untrusted* in the prompt.
- **What gets indexed**: the default branch only — on connect (full), on a merged PR (changed paths), and
  on `POST /api/repos/{id}/reindex`, via the `index-jobs` Redis Stream. PR head code is never written
  into the shared index.
- The offline provider has deterministic cross-file rules (`local.caller-arity`,
  `local.removed-still-called`, `local.null-return-deref`) that only fire with retrieved callers — which
  makes the value of retrieval measurable.

## Consequences

- The eval proves it: cross-file recall is 1.0 with repository context and 0.0 without
  (`run_eval.py --ablate-retrieval` fails CI if context stops helping).
- One datastore fewer than a dedicated vector DB; HNSW is fast enough for per-repo indexes of this size.
- Hashing embeddings are weaker semantically than a learned model; exact symbol (caller/test) lookup
  carries most of the value, and OpenAI embeddings are one env var away.
- Changing the dimension means re-creating the column and re-indexing — deliberately pinned at 512.
