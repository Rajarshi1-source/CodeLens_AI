"""Feedback flywheel (G7): turn comments reviewers consistently dismissed into negative eval candidates.

Pulls GET /api/internal/feedback/export (NDJSON, one dismissed finding per line with its diff context) and
writes eval/negatives.jsonl in the testset format, each with `must_not_flag` set to the dismissed line.
These are CANDIDATES: a human reads them and moves the genuine false positives into build_testset.py —
never append them to the test set automatically (a dismissal can also mean "true but not worth fixing").

    python eval/export_negatives.py --base-url http://localhost:8080 [--repo-id 1] [--min-dismissals 2]
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from reviewer_client import ReviewerClient

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "negatives.jsonl")


def to_candidate(row: dict) -> dict:
    """Exported rows are already testset-shaped (id, diff, must_not_flag...); fill the remaining fields."""
    out = dict(row)
    out.setdefault("expected_findings", [])
    out.setdefault("context_files", {})
    out.setdefault("expected_risk", None)
    rules = sorted({str(m.get("rule_key")) for m in row.get("must_not_flag", []) if m.get("rule_key")})
    out["tags"] = ["from-feedback", *[f"rule:{r}" for r in rules]]
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default=os.environ.get("CODELENS_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--repo-id", type=int)
    parser.add_argument("--min-dismissals", type=int, default=2)
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()

    client = ReviewerClient(args.base_url)
    ndjson = client.export_negatives(args.repo_id, args.min_dismissals)
    rows = [json.loads(line) for line in ndjson.splitlines() if line.strip()]
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(to_candidate(row), ensure_ascii=False) + "\n")
    print(f"wrote {len(rows)} negative candidate(s) -> {args.out} (review them before adding to build_testset.py)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
