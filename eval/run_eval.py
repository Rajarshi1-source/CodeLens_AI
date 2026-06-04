"""CodeLens AI review-quality eval runner.

Loads testset.jsonl, sends each diff to the backend, scores precision/recall/F1 on the planted bugs,
and prints a per-case table. Use --ci to enforce absolute thresholds + a no-regression check against
baseline.json (fails non-zero so the GitHub Actions gate blocks merges that degrade review quality).
Use --save-baseline to snapshot the current metrics as the new baseline.

Examples:
    python run_eval.py --base-url http://localhost:8080
    python run_eval.py --ci
    python run_eval.py --save-baseline
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from eval_types import CaseResult, Metrics, ReviewComment, TestCase
from reviewer_client import ReviewerClient

HERE = os.path.dirname(os.path.abspath(__file__))
TESTSET = os.path.join(HERE, "testset.jsonl")
BASELINE = os.path.join(HERE, "baseline.json")

# Absolute quality floor for the CI gate.
MIN_PRECISION = 0.80
MIN_RECALL = 0.80
# Allowed drop vs. baseline before we call it a regression.
REGRESSION_TOLERANCE = 0.05


def load_testset(path: str) -> list[TestCase]:
    cases = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                cases.append(TestCase.from_json(json.loads(line)))
    return cases


def score_case(case: TestCase, comments: list[ReviewComment]) -> CaseResult:
    result = CaseResult(case_id=case.id, category=case.category, comments=comments)
    flagged_lines = {c.line: c for c in comments}

    if case.is_clean:
        # Any comment on a clean diff is a false positive.
        result.false_positives = len(comments)
        return result

    matched_lines: set[int] = set()
    for expected in case.expected_findings:
        comment = flagged_lines.get(expected.line)
        if comment is not None and expected.satisfied_by(comment.severity):
            result.true_positives += 1
            matched_lines.add(expected.line)
        else:
            result.false_negatives += 1

    # Comments on lines we did not plant a bug on are false positives.
    expected_lines = {f.line for f in case.expected_findings}
    for comment in comments:
        if comment.line not in expected_lines:
            result.false_positives += 1
    return result


def run(client: ReviewerClient, cases: list[TestCase]) -> list[CaseResult]:
    results = []
    for case in cases:
        comments = client.review_diff(case.diff, case.language)
        results.append(score_case(case, comments))
    return results


def print_report(results: list[CaseResult], metrics: Metrics) -> None:
    print("\nCodeLens AI — review-quality eval")
    print("=" * 64)
    print(f"{'case':<26}{'cat':<12}{'TP':>4}{'FP':>4}{'FN':>4}")
    print("-" * 64)
    for r in results:
        print(f"{r.case_id:<26}{r.category:<12}{r.true_positives:>4}{r.false_positives:>4}{r.false_negatives:>4}")
    print("-" * 64)
    print(f"precision={metrics.precision:.3f}  recall={metrics.recall:.3f}  f1={metrics.f1:.3f}  "
          f"(TP={metrics.true_positives} FP={metrics.false_positives} FN={metrics.false_negatives})")
    print("=" * 64)


def load_baseline() -> dict | None:
    if not os.path.exists(BASELINE):
        return None
    with open(BASELINE, "r", encoding="utf-8") as fh:
        return json.load(fh)


def save_baseline(metrics: Metrics) -> None:
    with open(BASELINE, "w", encoding="utf-8") as fh:
        json.dump(metrics.to_json(), fh, indent=2)
        fh.write("\n")
    print(f"Saved baseline -> {BASELINE}")


def enforce_gate(metrics: Metrics) -> bool:
    ok = True
    if metrics.precision < MIN_PRECISION:
        print(f"GATE FAIL: precision {metrics.precision:.3f} < {MIN_PRECISION}")
        ok = False
    if metrics.recall < MIN_RECALL:
        print(f"GATE FAIL: recall {metrics.recall:.3f} < {MIN_RECALL}")
        ok = False

    baseline = load_baseline()
    if baseline:
        if metrics.precision < baseline["precision"] - REGRESSION_TOLERANCE:
            print(f"GATE FAIL: precision regressed {baseline['precision']:.3f} -> {metrics.precision:.3f}")
            ok = False
        if metrics.recall < baseline["recall"] - REGRESSION_TOLERANCE:
            print(f"GATE FAIL: recall regressed {baseline['recall']:.3f} -> {metrics.recall:.3f}")
            ok = False
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description="CodeLens AI review-quality eval")
    parser.add_argument("--base-url", default=os.environ.get("CODELENS_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--ci", action="store_true", help="enforce thresholds + no-regression; exit non-zero on fail")
    parser.add_argument("--save-baseline", action="store_true", help="write current metrics to baseline.json")
    args = parser.parse_args()

    client = ReviewerClient(args.base_url)
    if not client.health():
        print(f"Backend not reachable / unhealthy at {args.base_url}")
        return 2

    cases = load_testset(TESTSET)
    results = run(client, cases)
    metrics = Metrics.compute(results)
    print_report(results, metrics)

    if args.save_baseline:
        save_baseline(metrics)

    if args.ci:
        if enforce_gate(metrics):
            print("GATE PASS")
            return 0
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
