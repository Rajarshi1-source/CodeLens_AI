"""Unit tests for the eval harness itself (no backend needed):  python -m pytest eval/test_scoring.py"""

from __future__ import annotations

import json
import os

from eval_types import Metrics, ReviewComment, ReviewResult, TestCase
from run_eval import TESTSET, gate_failures, load_testset, score_case


def comment(line: int, severity: str = "CRITICAL", file: str = "src/A.java") -> ReviewComment:
    return ReviewComment(file=file, line=line, severity=severity, comment="x", confidence=0.9)


def case(**over) -> TestCase:
    base = {
        "id": "c",
        "diff": "",
        "category": "security",
        "expected_findings": [{"line": 5, "min_severity": "WARNING", "file": "src/A.java"}],
        "must_not_flag": [{"line": 9, "file": "src/A.java"}],
        "expected_risk": "MEDIUM",
    }
    base.update(over)
    return TestCase.from_json(base)


def review(*comments: ReviewComment, tokens: int = 100, risk: str = "MEDIUM") -> ReviewResult:
    return ReviewResult(comments=list(comments), input_tokens=tokens, output_tokens=0, risk_level=risk)


def test_match_requires_file_line_and_severity():
    assert score_case(case(), review(comment(5))).true_positives == 1
    # right line, wrong file -> miss + false positive
    r = score_case(case(), review(comment(5, file="src/B.java")))
    assert (r.true_positives, r.false_negatives, r.false_positives) == (0, 1, 1)
    # right anchor, severity below the minimum -> miss, but not a false positive
    r = score_case(case(), review(comment(5, severity="SUGGESTION")))
    assert (r.true_positives, r.false_negatives, r.false_positives) == (0, 1, 0)


def test_expectation_without_file_matches_any_file():
    c = case(expected_findings=[{"line": 5}])
    assert score_case(c, review(comment(5, severity="SUGGESTION", file="x/y.py"))).true_positives == 1


def test_must_not_flag_is_counted_as_false_positive_and_hit():
    r = score_case(case(), review(comment(5), comment(9)))
    assert (r.true_positives, r.false_positives, r.must_not_flag_hits) == (1, 1, 1)


def test_clean_case_any_comment_is_a_false_positive():
    r = score_case(case(expected_findings=[], must_not_flag=[]), review(comment(1)))
    assert (r.false_positives, r.false_negatives) == (1, 0)


def test_metrics_cross_file_risk_and_tokens():
    xf = case(id="xf", tags=["cross-file"])
    plain = case(id="p", expected_risk="HIGH")
    results = [score_case(xf, review(comment(5), tokens=300)),
               score_case(plain, review(tokens=100, risk="LOW"))]
    m = Metrics.compute(results)
    assert m.cross_file_recall == 1.0
    assert m.recall == 0.5
    assert m.risk_accuracy == 0.5
    assert m.mean_tokens_per_review == 200.0


def perfect(**over) -> Metrics:
    m = Metrics(1.0, 1.0, 1.0, 10, 0, 0, 10, cross_file_recall=1.0, must_not_flag_hits=0,
                risk_accuracy=1.0, mean_tokens_per_review=100.0)
    for k, v in over.items():
        setattr(m, k, v)
    return m


BASELINE = perfect().to_json()


def test_gate_passes_at_baseline():
    assert gate_failures(perfect(), BASELINE) == []


def test_cost_gate_allows_20_percent_then_fails():
    assert gate_failures(perfect(mean_tokens_per_review=120.0), BASELINE) == []
    fails = gate_failures(perfect(mean_tokens_per_review=121.0), BASELINE)
    assert len(fails) == 1 and fails[0].startswith("COST regression")


def test_gate_fails_on_floors_and_regressions():
    assert any("precision" in f for f in gate_failures(perfect(precision=0.7), BASELINE))
    assert any("cross-file recall" in f for f in gate_failures(perfect(cross_file_recall=0.5), BASELINE))
    assert any("must_not_flag" in f for f in gate_failures(perfect(must_not_flag_hits=1), BASELINE))
    assert any("risk_accuracy regressed" in f for f in gate_failures(perfect(risk_accuracy=0.85), BASELINE))


def test_ablation_must_show_that_context_helps():
    assert gate_failures(perfect(), BASELINE, ablated=perfect(cross_file_recall=0.0)) == []
    fails = gate_failures(perfect(), BASELINE, ablated=perfect(cross_file_recall=1.0))
    assert any("does not help" in f for f in fails)


def test_shipped_testset_is_well_formed():
    cases = load_testset(TESTSET)
    assert len(cases) >= 30
    assert len({c.id for c in cases}) == len(cases)
    assert sum(1 for c in cases if c.is_cross_file and not c.is_clean) >= 6
    assert sum(1 for c in cases if c.is_clean) >= 5
    for c in cases:
        assert c.diff.startswith("diff --git ")
        assert c.expected_risk in ("HIGH", "MEDIUM", "LOW")
        if c.is_cross_file:
            assert c.context_files, f"{c.id}: cross-file cases need context_files"


def test_testset_matches_its_generator():
    """testset.jsonl must be regenerated (python eval/build_testset.py) after editing the cases."""
    import build_testset

    with open(TESTSET, encoding="utf-8") as fh:
        shipped = [json.loads(line) for line in fh if line.strip()]
    assert shipped == build_testset.CASES, "run: python eval/build_testset.py"
    assert os.path.basename(build_testset.OUT) == "testset.jsonl"
