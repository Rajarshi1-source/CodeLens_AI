# ADR 0007 — Diff snapshots and inline comment anchoring

- Status: Accepted — supersedes the MVP "raw diff from GitHub, side-by-side, findings fallback" approach
- Date: 2026-10

## Context

The MVP fetched the unified diff from GitHub on every view (cached in Redis) and rendered it with
`react-diff-viewer-continued`, listing findings beside the diff. Problems: the diff shown could differ from
the diff that was reviewed (the PR moved on, the cache expired, the token lapsed); the viewer numbered
lines from 1 per hunk, so findings could not be placed on the line they talk about; and the library could
not render arbitrary rows (comments, presence markers) between diff lines.

## Decision

- **Snapshot the reviewed diff**: `pr_diffs(pr_id, head_sha, diff, source, byte_size)`.
  `DiffSnapshotService.getOrFetch` serves `pr_diffs` → GitHub → `""` (5 MB cap) and is used by both the
  review pipeline and `GET /api/prs/{id}/diff`, so the UI shows exactly what the model saw.
- **Own the renderer**: `parseDiff.ts` produces hunks whose rows carry **absolute** old/new line numbers
  from the `@@` headers (and tolerates blank context lines whose leading space was stripped).
  `DiffFileTable` renders split or unified mode as a fixed-layout table and inserts a full-width comment
  row directly after the new-file line each finding is anchored to; very large files collapse.
- **Keep the fallbacks**: findings whose line is not in the diff go under "Other findings"; when no diff is
  available at all, the findings list grouped by file is shown.
- Findings are keyed by a **fingerprint** (`sha256(file|line|ruleKey)`), which also lets feedback survive
  re-reviews (ADR 0008) and lets the live stream merge with persisted comments.

## Consequences

- What you see is what was reviewed, even after the PR or the GitHub token changes.
- Inline anchoring, suggestion blocks, feedback and apply buttons, and presence focus markers all live in
  the same table; one dependency fewer.
- Diffs are stored per reviewed head SHA — bounded by the 5 MB cap; old snapshots can be pruned with the
  sessions they belong to.
