# ADR 0008 — Explainable PR risk score (G3) and the feedback flywheel (G6, G7)

- Status: Accepted
- Date: 2026-10

## Context

Reviewers triage. A list of comments does not say which PR deserves attention first, and an AI reviewer
that keeps repeating a comment the team ignores loses trust. We wanted a risk signal people can argue
with, and a loop that turns reviewer reactions into fewer bad comments — without letting a model grade
itself.

## Decision

**Risk is deterministic and explainable.** `RiskScoringService` adds points per factor, each with a
human-readable reason; the LLM only writes the prose summary (with a template fallback).

| Factor | Points |
|---|---|
| Sensitive paths (auth, security, crypto, payments, migrations, config, CI) | +25 first, +5 each more, max 35 |
| Production change with no test changes | +15 |
| Test lines ≥ 30% of production lines | −10 |
| Size > 500 / > 200 changed lines | +15 / +8 |
| Max fan-in of a changed symbol ≥ 10 / ≥ 3 (from the G1 index) | +15 / +8 |
| Each CRITICAL / WARNING finding | +12 (max 36) / +4 (max 16) |
| Floor: any CRITICAL lifts the score to the MEDIUM threshold | the difference |

Clamped to 0–100; HIGH ≥ 60, MEDIUM ≥ 30. Risk is stored on the session and denormalized onto
`pull_requests` for filtering (`GET /api/prs?risk=`), the dashboard and the GitHub summary comment.

**Feedback flywheel.**
- Thumbs up/down per comment (`PUT/DELETE /api/comments/{id}/feedback`, one vote per user, upsert).
- Feedback is keyed by `(repo_id, fingerprint, rule_key)`: on re-review it is detached and re-linked to the
  new comment with the same fingerprint, so votes survive.
- **Suppression (G7)**: a rule with ≥ 5 votes and a dismiss rate ≥ 0.7 is suppressed for that repository
  (cached 5 min, counted in review metadata). The eval bypasses suppression.
- Dismissed comments are exported (`GET /api/internal/feedback/export`, `eval/export_negatives.py`) as
  `must_not_flag` *candidates* — a human curates them into the test set.

**Apply suggestion (G6).** `POST /api/comments/{id}/apply` posts a GitHub *suggested change*
(```` ```suggestion ```` block at the reviewed head SHA, right side) rather than committing: the author
stays in control. It is idempotent (an atomic `jsonb` claim), answers 422 without a suggestion and 409
when the PR head moved since the review; failed attempts can be retried.

## Consequences

- Risk is reproducible and debuggable; weights live in `RiskProperties` and are covered by table tests and
  by `expected_risk` in the eval (risk accuracy is gated).
- The model never decides what gets suppressed — humans do, through votes, per repository.
- A suggested change, not a commit, means no write access to code is needed beyond PR comments.
