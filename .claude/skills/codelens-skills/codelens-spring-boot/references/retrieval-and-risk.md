# CodeLens AI — Retrieval (G1), Risk (G3), Feedback (G7) and Apply (G6)

Load when touching `service/index`, `service/retrieval`, `service/embedding`, `service/code`, `service/risk`,
`service/feedback`, `service/suggestion`, or `CodeIndexRepository`. Decisions: ADR 0005 and ADR 0008.

## Contents
1. Indexing the default branch
2. Embeddings
3. Retrieval per chunk
4. Offline cross-file rules (why G1 is measurable)
5. Risk scoring
6. Feedback, re-linking and suppression
7. Apply suggestion

## 1. Indexing

- **What**: the repository's default branch only — never PR head code (it is unreviewed and per-PR).
- **When**: full index on connect; changed paths when a PR is closed+merged; `POST /api/repos/{id}/reindex`.
  All go through the Redis Stream `index-jobs` → `IndexJobConsumer` (consumer group, ACK after success, DLQ),
  mirroring `ReviewJobConsumer`.
- **How** (`RepoIndexService`): list the tree via `GitHubApiClient.getTree`, drop `DiffChunkerService.shouldSkip`
  paths and files over `codelens.indexing.max-file-bytes`, cap at `max-files`, fetch contents 8-way on virtual
  threads, cut each file with `CodeChunker` (structure-aware units from `CodeStructureParsers`), embed in batches,
  then per file `upsertChunk` (unique `(repo_id, path, content_hash)`), `replaceSymbols` (DEF/REF rows) and
  `deleteStale`. `indexFromContents` does the same from a `Map<path, content>` (demo + eval).
- Status on `repositories`: `index_status` `INDEXING` → `READY` | `FAILED`, `indexed_sha`, `indexed_at`,
  `indexed_chunks`. Metrics: `index.chunks`, `index.duration`.

## 2. Embeddings

```java
public sealed interface EmbeddingProvider permits HashingEmbeddingProvider, OpenAiEmbeddingProvider {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
    int dimensions();       // must be 512 - startup fails fast otherwise (schema is vector(512))
    String providerId();    // stored in code_chunks.embedding_provider; only like-with-like is compared
}
```

- `hashing` (default, `hashing-v1`): identifier tokenization (camelCase/snake_case split), FNV-1a signed feature
  hashing into 512 buckets, log term frequency, L2 normalization. Deterministic and offline.
- `openai`: `text-embedding-3-small` with `dimensions: 512`, called through a separate `EmbeddingClient` bean so its
  Resilience4j annotations apply.
- Switching providers means re-indexing (vectors from different providers are not comparable).

## 3. Retrieval (`ContextRetrievalService`)

`sourceFor(repoId)` → `PgVectorContextSource` when the repo's index is `READY`, else `ContextSource.EMPTY`;
`inMemory(files)` → `InMemoryContextSource` (eval endpoint, demo, tests). `retrieveAll(source, chunks, changedPaths)`
embeds all chunks in one batch, then per chunk ranks:

1. **callers** — chunks whose REF symbols include the chunk's changed `symbols` (`code_symbols` join);
2. **tests** — caller-like chunks under test paths;
3. **neighbours** — `ORDER BY embedding <=> CAST(:q AS vector) LIMIT :k`.

Changed paths are excluded (the diff already shows them); snippets are capped at 40 lines and formatted
`// path:start-end (symbol)\n<code>`; the total per chunk is capped by `codelens.retrieval.max-context-tokens`.
The prompt marks them as untrusted *RELATED* sections. Kill switch `codelens.retrieval.enabled`. Metrics
`retrieval.latency`, `retrieval.snippets`. `callerCounts(...)` feeds the risk score's fan-in factor.

JS/TS gotcha: calls inside template-literal interpolations (`` `${formatPrice(total)}` ``) are code — the brace
parser keeps `${...}` while blanking the rest of the string, otherwise those callers are invisible.

## 4. Offline cross-file rules (`LocalContextRules`)

Fire only when retrieval supplied callers — a diff-only reviewer structurally cannot see these:

| Rule | Fires when | Severity |
|---|---|---|
| `local.caller-arity` | a declaration's parameter count changed and a caller passes a different count | CRITICAL |
| `local.removed-still-called` | a declaration was removed/renamed and a caller still calls it | CRITICAL |
| `local.null-return-deref` | a method newly `return null;`s and a caller dereferences the result unguarded | WARNING |

The eval proves the value: cross-file recall 1.0 with context vs 0.0 without (`run_eval.py --ablate-retrieval`).

## 5. Risk scoring (`RiskScoringService`)

Pure and deterministic; the LLM only writes the summary. Input: the parsed file diffs, the kept findings, and the
caller counts of changed symbols.

| Code | Factor | Points |
|---|---|---|
| `SENSITIVE_PATHS` | auth/security/crypto/payments/migrations/config/CI (`codelens.risk.sensitive-paths`) | +25 first, +5 each more, max 35 |
| `NO_TESTS` | production change with no test changes | +15 |
| `TESTS_INCLUDED` | test lines ≥ 30% of production lines | −10 |
| `LARGE_DIFF` | > 500 / > 200 changed lines | +15 / +8 |
| `HIGH_FAN_IN` | max callers of a changed symbol ≥ 10 / ≥ 3 | +15 / +8 |
| `CRITICAL_FINDINGS` / `WARNING_FINDINGS` | per finding | +12 (max 36) / +4 (max 16) |
| `CRITICAL_FLOOR` | any CRITICAL and score < MEDIUM threshold | lifts to the threshold |

Clamp 0–100; HIGH ≥ 60, MEDIUM ≥ 30. The reasons always sum to the score. Persist on the session
(`risk_score`, `risk_level`, `risk_reasons`, `recommended_action`) and denormalize `risk_level`/`risk_score`/
`latest_session_id` onto `pull_requests` in the same transaction. `ReviewSummaryService` writes the prose summary
(provider `summarize`, template fallback); `ReviewCommentFormatter` renders the GitHub summary comment.

## 6. Feedback, re-linking, suppression

- `PUT /api/comments/{id}/feedback {vote: 1|-1}` upserts `ON CONFLICT (comment_id, user_id)`; `DELETE` clears.
  Rows carry `repo_id`, `rule_key`, `fingerprint` (denormalized from the comment).
- Re-review: `startOrReplaceSession` sets `comment_id = NULL` on the old session's feedback before its comments are
  deleted; `completeReview` re-links rows to the new comment with the same `(repo_id, fingerprint)`
  (`DISTINCT ON` picks one comment per fingerprint). Votes survive.
- `SuppressionService.suppressedRuleKeys(repoId)`: rules with ≥ `min-votes` (5) votes and dismiss rate ≥
  `dismiss-rate` (0.7); cached 5 minutes; applied in `validateAndFilter` (count stored in metadata). The eval
  endpoint bypasses it.
- `FeedbackExportService` → `GET /api/internal/feedback/export?minDismissals=` (NDJSON, testset-shaped
  `must_not_flag` candidates) → `eval/export_negatives.py`. Humans curate; nothing is appended automatically.

## 7. Apply suggestion (`SuggestionApplyService`)

`POST /api/comments/{id}/apply`:
1. 422 if the comment has no `code_suggestion`; 409 if the session's `head_sha` is not the PR's current head.
2. Atomic claim: `UPDATE review_comments SET metadata = jsonb_set(..., '{apply}', '{"state":"PENDING"}') WHERE id = :id
   AND (no apply yet OR state = 'FAILED')` — exactly one caller proceeds; others get the current state.
3. `GitHubApiClient.createReviewComment(repo, prNumber, headSha, path, line, body)` with a
   ```` ```suggestion ```` block, `side=RIGHT`, `commit_id=headSha`.
4. Store `{state: APPLIED, htmlUrl}` (or `FAILED`, retryable). Counter `suggestion.apply{result}`.

It posts a *suggested change*, not a commit: the author stays in control and no write access to code is needed.
