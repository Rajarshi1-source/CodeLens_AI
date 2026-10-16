# CodeLens AI — PostgreSQL Schema Reference

The complete schema for CodeLens AI. Load when creating entities/migrations, writing queries, or reasoning about indexes and constraints. PostgreSQL 16 + pgvector (`pgvector/pgvector:pg16`). The `UNIQUE(pr_id, head_sha)` on `review_sessions` is the idempotency backstop referenced throughout the backend.

## Contents
1. Tables (DDL)
2. Indexes
3. Key queries
4. JPA entity mapping notes
5. Differentiator tables (G1, G3, G7, diff snapshots, membership)
6. Schema evolution: idempotent `schema.sql` + expand-contract

## 1. Tables (DDL)

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    github_id BIGINT UNIQUE NOT NULL,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    avatar_url VARCHAR(500),
    access_token VARCHAR(255) NOT NULL,         -- ENCRYPTED at rest; never in a DTO
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE repositories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    github_repo_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    webhook_id BIGINT,
    webhook_secret VARCHAR(255),                -- ENCRYPTED at rest; never in a DTO
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, github_repo_id)
);

CREATE TABLE pull_requests (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT REFERENCES repositories(id) ON DELETE CASCADE,
    github_pr_id BIGINT NOT NULL,
    pr_number INT NOT NULL,
    title VARCHAR(500) NOT NULL,
    author VARCHAR(100) NOT NULL,
    branch_from VARCHAR(255),
    branch_to VARCHAR(255),
    head_sha VARCHAR(40),                        -- idempotency key component
    status VARCHAR(20) DEFAULT 'PENDING',
    diff_url VARCHAR(1000),
    html_url VARCHAR(1000),
    files_changed INT DEFAULT 0,
    additions INT DEFAULT 0,
    deletions INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(repo_id, github_pr_id)
);

CREATE TABLE review_sessions (
    id BIGSERIAL PRIMARY KEY,
    pr_id BIGINT REFERENCES pull_requests(id) ON DELETE CASCADE,
    head_sha VARCHAR(40) NOT NULL,               -- idempotency key component
    status VARCHAR(20) DEFAULT 'IN_PROGRESS',
    model_used VARCHAR(50) DEFAULT 'gpt-5',      -- logs the ACTUAL model used
    prompt_version VARCHAR(20),                  -- which prompt produced this review
    total_tokens INT DEFAULT 0,
    cost_usd NUMERIC(10,6) DEFAULT 0,
    review_time_ms INT DEFAULT 0,
    summary TEXT,
    metadata JSONB DEFAULT '{}',
    started_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    UNIQUE(pr_id, head_sha)                      -- EFFECTIVELY-ONCE backstop
);

CREATE TABLE review_comments (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT REFERENCES review_sessions(id) ON DELETE CASCADE,
    pr_id BIGINT REFERENCES pull_requests(id) ON DELETE CASCADE,
    file_path VARCHAR(1000) NOT NULL,
    line_number INT NOT NULL,
    severity VARCHAR(20) NOT NULL,
    comment_text TEXT NOT NULL,
    code_suggestion TEXT,
    confidence NUMERIC(4,3),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE comment_feedback (                  -- feedback flywheel
    id BIGSERIAL PRIMARY KEY,
    comment_id BIGINT REFERENCES review_comments(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id),
    vote SMALLINT NOT NULL,                      -- -1 dismiss / +1 helpful
    created_at TIMESTAMP DEFAULT NOW()
);
```

## 2. Indexes

```sql
CREATE INDEX idx_pr_status   ON pull_requests(status);
CREATE INDEX idx_pr_repo     ON pull_requests(repo_id);
CREATE INDEX idx_session_pr  ON review_sessions(pr_id);
CREATE INDEX idx_comment_pr        ON review_comments(pr_id);
CREATE INDEX idx_comment_severity  ON review_comments(severity);
CREATE INDEX idx_comment_search    ON review_comments
    USING GIN (to_tsvector('english', comment_text));   -- full-text search
```

## 3. Key queries

```sql
-- All comments for a PR with session metadata
SELECT rc.*, rs.model_used, rs.review_time_ms
FROM review_comments rc JOIN review_sessions rs ON rc.session_id = rs.id
WHERE rc.pr_id = ? ORDER BY rc.file_path, rc.line_number;

-- Dashboard: severity distribution for a user's repos
SELECT rc.severity, COUNT(*) FROM review_comments rc
JOIN pull_requests pr ON rc.pr_id = pr.id
JOIN repositories r ON pr.repo_id = r.id
WHERE r.user_id = ? GROUP BY rc.severity;

-- Comment search (uses the GIN index)
SELECT * FROM review_comments
WHERE pr_id = ? AND to_tsvector('english', comment_text) @@ plainto_tsquery(?);

-- Avg + p95 review time over last 24h
SELECT AVG(review_time_ms),
       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY review_time_ms)
FROM review_sessions WHERE completed_at > NOW() - INTERVAL '24 hours';
```

## 4. JPA entity mapping notes

- Map `metadata JSONB` with a JSON type (`@JdbcTypeCode(SqlTypes.JSON)` on a `Map<String,Object>` or a typed record). Don't map it as `String` unless you genuinely treat it as opaque.
- `status` columns map to `@Enumerated(EnumType.STRING)`-backed enums (`ReviewStatus`) — but the column is `VARCHAR`, so keep enum names aligned with stored values.
- `confidence NUMERIC(4,3)` → `BigDecimal` in the entity; convert to `double` only in the DTO.
- Never add `access_token` / `webhook_secret` to any DTO or `toString()`.
- Review completion writes PR status + session + comments in ONE `@Transactional` method (the CP plane — no ghost states).

## 5. Differentiator tables (G1, G3, G7, diff snapshots, membership)

These are the exact statements from `codelensai/src/main/resources/schema.sql` — expand-only changes on top of
§1. `code_chunks.embedding` is `vector(512)` with an **HNSW** `vector_cosine_ops` index (no IVFFlat: HNSW needs
no training data and stays accurate as the index grows). `code_chunks` / `code_symbols` are read and written
through `JdbcClient` (`CAST(:v AS vector)`, `ORDER BY embedding <=> CAST(:q AS vector)`), not JPA.

```sql
-- ---------------------------------------------------------------------------------------------
-- Differentiator features (expand-only changes; see docs/adr/0005-0009)
-- ---------------------------------------------------------------------------------------------

-- Repo index state (G1) + default branch.
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS default_branch VARCHAR(255);
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS index_status VARCHAR(20) DEFAULT 'NOT_INDEXED';
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS indexed_sha VARCHAR(40);
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS indexed_at TIMESTAMP;
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS indexed_chunks INT DEFAULT 0;

-- Access is by membership, not just the connecting user. The owner is backfilled on every boot.
CREATE TABLE IF NOT EXISTS repository_members (
    repo_id BIGINT NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL DEFAULT 'OWNER',
    created_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (repo_id, user_id)
);
INSERT INTO repository_members(repo_id, user_id, role)
    SELECT id, user_id, 'OWNER' FROM repositories WHERE user_id IS NOT NULL
    ON CONFLICT DO NOTHING;

-- Latest review denormalized onto the PR so lists/filters need no N+1 (G3).
ALTER TABLE pull_requests ADD COLUMN IF NOT EXISTS latest_session_id BIGINT;
ALTER TABLE pull_requests ADD COLUMN IF NOT EXISTS risk_level VARCHAR(10);
ALTER TABLE pull_requests ADD COLUMN IF NOT EXISTS risk_score SMALLINT;
CREATE INDEX IF NOT EXISTS idx_pr_risk ON pull_requests(risk_level);

-- Risk (G3), real token accounting + cost, failure reason (budget).
ALTER TABLE review_sessions ADD COLUMN IF NOT EXISTS risk_score SMALLINT;
ALTER TABLE review_sessions ADD COLUMN IF NOT EXISTS risk_level VARCHAR(10);
ALTER TABLE review_sessions ADD COLUMN IF NOT EXISTS risk_reasons JSONB DEFAULT '[]';
ALTER TABLE review_sessions ADD COLUMN IF NOT EXISTS recommended_action TEXT;
ALTER TABLE review_sessions ADD COLUMN IF NOT EXISTS input_tokens INT DEFAULT 0;
ALTER TABLE review_sessions ADD COLUMN IF NOT EXISTS output_tokens INT DEFAULT 0;
ALTER TABLE review_sessions ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(40);
ALTER TABLE review_sessions ALTER COLUMN model_used TYPE VARCHAR(100);

-- Rule key (G7 suppression) + stable fingerprint (feedback re-link, stream key).
ALTER TABLE review_comments ADD COLUMN IF NOT EXISTS rule_key VARCHAR(100);
ALTER TABLE review_comments ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_comment_session ON review_comments(session_id);
CREATE INDEX IF NOT EXISTS idx_comment_rule ON review_comments(rule_key);

-- Feedback flywheel: denormalized so it survives a same-SHA re-review and powers suppression.
ALTER TABLE comment_feedback ADD COLUMN IF NOT EXISTS repo_id BIGINT;
ALTER TABLE comment_feedback ADD COLUMN IF NOT EXISTS rule_key VARCHAR(100);
ALTER TABLE comment_feedback ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64);
ALTER TABLE comment_feedback ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT NOW();
DELETE FROM comment_feedback a USING comment_feedback b
    WHERE a.comment_id = b.comment_id AND a.user_id = b.user_id AND a.id < b.id;
CREATE UNIQUE INDEX IF NOT EXISTS uq_feedback_comment_user ON comment_feedback(comment_id, user_id);
CREATE INDEX IF NOT EXISTS idx_feedback_repo_rule ON comment_feedback(repo_id, rule_key);
CREATE INDEX IF NOT EXISTS idx_feedback_fingerprint ON comment_feedback(repo_id, fingerprint);

-- Diff snapshots: the diff a review ran against survives cache expiry / a missing token / demo mode.
CREATE TABLE IF NOT EXISTS pr_diffs (
    pr_id BIGINT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    head_sha VARCHAR(40) NOT NULL,
    diff TEXT NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'github',
    byte_size INT NOT NULL DEFAULT 0,
    fetched_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (pr_id, head_sha)
);

-- G1 code index: structure-aware chunks of the default branch + their embeddings and symbols.
CREATE TABLE IF NOT EXISTS code_chunks (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    path VARCHAR(1000) NOT NULL,
    language VARCHAR(20),
    symbol VARCHAR(255),
    kind VARCHAR(20) NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    ref_sha VARCHAR(40),
    embedding vector(512),
    embedding_provider VARCHAR(40) NOT NULL,
    indexed_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (repo_id, path, content_hash)
);
CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_path ON code_chunks(repo_id, path);
CREATE INDEX IF NOT EXISTS idx_code_chunks_embedding ON code_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS code_symbols (
    chunk_id BIGINT NOT NULL REFERENCES code_chunks(id) ON DELETE CASCADE,
    repo_id BIGINT NOT NULL,
    symbol VARCHAR(255) NOT NULL,
    role VARCHAR(3) NOT NULL,
    PRIMARY KEY (chunk_id, symbol, role)
);
CREATE INDEX IF NOT EXISTS idx_code_symbols_lookup ON code_symbols(repo_id, symbol, role);
```

Typical retrieval queries (`CodeIndexRepository`):

```sql
-- callers of the changed symbols (exact, ranked first)
SELECT c.* FROM code_symbols s JOIN code_chunks c ON c.id = s.chunk_id
WHERE s.repo_id = :repo AND s.role = 'REF' AND s.symbol IN (:symbols) AND c.path NOT IN (:changed);

-- nearest neighbours of a chunk's embedding
SELECT * FROM code_chunks
WHERE repo_id = :repo AND path NOT IN (:changed)
ORDER BY embedding <=> CAST(:q AS vector) LIMIT :k;
```

## 6. Schema evolution: idempotent `schema.sql` + expand-contract

There is no migration framework in this project. `schema.sql` runs on **every** start
(`spring.sql.init.mode=always`), so every statement must be safe to re-run:

```sql
-- CodeLens AI — PostgreSQL schema (idempotent: safe to re-run on every boot)
-- UNIQUE(pr_id, head_sha) on review_sessions is the effectively-once backstop.
--
-- Rules for editing this file (it runs on EVERY startup via spring.sql.init.mode=always):
--   * every statement must be idempotent: CREATE ... IF NOT EXISTS / ADD COLUMN IF NOT EXISTS;
--   * no DO $$ ... $$ blocks — Spring's ScriptUtils splits on ';';
--   * dedupe rows BEFORE adding a unique index, or startup fails on existing data.

-- pgvector (G1 repo-aware retrieval). Needs the extension installed in the server image
-- (pgvector/pgvector:pg16) and, on a self-managed server, created once by a superuser.
CREATE EXTENSION IF NOT EXISTS vector;
```

- `CREATE TABLE/INDEX IF NOT EXISTS`, `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, `INSERT ... ON CONFLICT DO NOTHING`.
- **No `DO $$ ... $$` blocks** — Spring's `ScriptUtils` splits the script on `;`.
- **Dedupe before a unique index**: add the `DELETE` of duplicates *above* the `CREATE UNIQUE INDEX`, or startup
  fails on existing data (done for `comment_feedback(comment_id, user_id)`).
- `SchemaIdempotencyIT`-style check: running the script twice must succeed.

For changes during rolling deploys, keep expand-contract:

1. **Expand** — add the new nullable column/table (old code ignores it).
2. **Deploy** code that writes both old and new.
3. **Backfill** existing rows (an idempotent `UPDATE ... WHERE new IS NULL` is fine in `schema.sql`).
4. **Contract** — in a later release, drop the old column once no running code reads it.

Never drop or rename a column in the same release that stops writing it — a mid-rollout pod on the old version would break.
