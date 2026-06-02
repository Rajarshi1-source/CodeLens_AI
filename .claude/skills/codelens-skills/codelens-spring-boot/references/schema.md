# CodeLens AI — PostgreSQL Schema Reference

The complete schema for CodeLens AI. Load when creating entities/migrations, writing queries, or reasoning about indexes and constraints. PostgreSQL 16 (+ pgvector for the G1 differentiator). The `UNIQUE(pr_id, head_sha)` on `review_sessions` is the idempotency backstop referenced throughout the backend.

## Contents
1. Tables (DDL)
2. Indexes
3. Key queries
4. JPA entity mapping notes
5. pgvector (G1 repo-aware context)
6. Migration conventions (expand-contract)

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

## 5. pgvector (G1 repo-aware context)

Add the extension to the existing Postgres rather than standing up a separate vector DB:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE repo_embeddings (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT REFERENCES repositories(id) ON DELETE CASCADE,
    file_path VARCHAR(1000) NOT NULL,
    symbol VARCHAR(255),                  -- function/class name
    chunk_text TEXT NOT NULL,
    embedding vector(1536),               -- match your embedding model's dim
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_repo_embeddings_ann ON repo_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- Top-k related chunks for a changed symbol
SELECT file_path, symbol, chunk_text
FROM repo_embeddings
WHERE repo_id = ?
ORDER BY embedding <=> ?      -- cosine distance to the query embedding
LIMIT 8;
```

## 6. Migration conventions (expand-contract)

Use Flyway or Liquibase. For schema changes during rolling deploys, expand-contract keeps every in-flight pod working:

1. **Expand** — add the new nullable column/table (old code ignores it).
2. **Deploy** code that writes both old and new.
3. **Backfill** existing rows.
4. **Contract** — in a later release, drop the old column once no running code reads it.

Never drop or rename a column in the same release that stops writing it — a mid-rollout pod on the old version would break.
