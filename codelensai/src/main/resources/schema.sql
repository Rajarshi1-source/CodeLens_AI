-- CodeLens AI — PostgreSQL schema (idempotent: safe to re-run)
-- UNIQUE(pr_id, head_sha) on review_sessions is the effectively-once backstop.

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    github_id BIGINT UNIQUE NOT NULL,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    avatar_url VARCHAR(500),
    access_token VARCHAR(512) NOT NULL,          -- ENCRYPTED at rest; never in a DTO
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS repositories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    github_repo_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    webhook_id BIGINT,
    webhook_secret VARCHAR(512),                 -- ENCRYPTED at rest; never in a DTO
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, github_repo_id)
);

CREATE TABLE IF NOT EXISTS pull_requests (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT REFERENCES repositories(id) ON DELETE CASCADE,
    github_pr_id BIGINT NOT NULL,
    pr_number INT NOT NULL,
    title VARCHAR(500) NOT NULL,
    author VARCHAR(100) NOT NULL,
    branch_from VARCHAR(255),
    branch_to VARCHAR(255),
    head_sha VARCHAR(40),                         -- idempotency key component
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

CREATE TABLE IF NOT EXISTS review_sessions (
    id BIGSERIAL PRIMARY KEY,
    pr_id BIGINT REFERENCES pull_requests(id) ON DELETE CASCADE,
    head_sha VARCHAR(40) NOT NULL,                -- idempotency key component
    status VARCHAR(20) DEFAULT 'IN_PROGRESS',
    model_used VARCHAR(50) DEFAULT 'local-mock',  -- logs the ACTUAL model used
    prompt_version VARCHAR(20),
    total_tokens INT DEFAULT 0,
    cost_usd NUMERIC(10,6) DEFAULT 0,
    review_time_ms INT DEFAULT 0,
    summary TEXT,
    metadata JSONB DEFAULT '{}',
    started_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    UNIQUE(pr_id, head_sha)                       -- EFFECTIVELY-ONCE backstop
);

CREATE TABLE IF NOT EXISTS review_comments (
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

CREATE TABLE IF NOT EXISTS comment_feedback (
    id BIGSERIAL PRIMARY KEY,
    comment_id BIGINT REFERENCES review_comments(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id),
    vote SMALLINT NOT NULL,                       -- -1 dismiss / +1 helpful
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pr_status        ON pull_requests(status);
CREATE INDEX IF NOT EXISTS idx_pr_repo          ON pull_requests(repo_id);
CREATE INDEX IF NOT EXISTS idx_session_pr       ON review_sessions(pr_id);
CREATE INDEX IF NOT EXISTS idx_comment_pr       ON review_comments(pr_id);
CREATE INDEX IF NOT EXISTS idx_comment_severity ON review_comments(severity);
CREATE INDEX IF NOT EXISTS idx_comment_search   ON review_comments
    USING GIN (to_tsvector('english', comment_text));
