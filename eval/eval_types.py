"""Shared types for the CodeLens AI review-quality eval harness.

The harness measures "how good are the reviews?" against a fixed test set of diffs with planted bugs
(and clean controls). It calls the backend's internal eval endpoint and scores precision/recall on
whether the planted (file, line)s were flagged, whether lines marked `must_not_flag` were left alone,
whether the PR risk band is right, and what each review cost in tokens. With the local (mock) provider
this is fully deterministic and runs offline, which is what makes it usable as a CI gate.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

SEVERITY_RANK = {"SUGGESTION": 1, "WARNING": 2, "CRITICAL": 3}


def _file_matches(expected: Optional[str], actual: str) -> bool:
    """An expectation without a file matches any file; otherwise paths must be equal (suffix-tolerant)."""
    if not expected:
        return True
    return actual == expected or actual.endswith("/" + expected) or expected.endswith("/" + actual)


@dataclass(frozen=True)
class ExpectedFinding:
    """A planted bug we expect the reviewer to flag."""

    line: int
    min_severity: str = "SUGGESTION"
    file: Optional[str] = None

    def satisfied_by(self, severity: str) -> bool:
        return SEVERITY_RANK.get(severity, 0) >= SEVERITY_RANK.get(self.min_severity, 0)

    def anchors(self, comment: "ReviewComment") -> bool:
        return comment.line == self.line and _file_matches(self.file, comment.file)


@dataclass(frozen=True)
class Anchor:
    """A (file, line) that a good reviewer must NOT comment on."""

    line: int
    file: Optional[str] = None

    def anchors(self, comment: "ReviewComment") -> bool:
        return comment.line == self.line and _file_matches(self.file, comment.file)


@dataclass(frozen=True)
class TestCase:
    __test__ = False  # not a pytest class

    id: str
    diff: str
    language: str
    category: str
    expected_findings: list[ExpectedFinding]
    must_not_flag: list[Anchor] = field(default_factory=list)
    context_files: dict[str, str] = field(default_factory=dict)
    expected_risk: Optional[str] = None
    tags: list[str] = field(default_factory=list)

    @property
    def is_clean(self) -> bool:
        return len(self.expected_findings) == 0

    @property
    def is_cross_file(self) -> bool:
        return "cross-file" in self.tags

    @staticmethod
    def from_json(obj: dict) -> "TestCase":
        return TestCase(
            id=obj["id"],
            diff=obj["diff"],
            language=obj.get("language", "java"),
            category=obj.get("category", "uncategorized"),
            expected_findings=[
                ExpectedFinding(line=f["line"], min_severity=f.get("min_severity", "SUGGESTION"), file=f.get("file"))
                for f in obj.get("expected_findings", [])
            ],
            must_not_flag=[Anchor(line=a["line"], file=a.get("file")) for a in obj.get("must_not_flag", [])],
            context_files=obj.get("context_files") or {},
            expected_risk=obj.get("expected_risk"),
            tags=obj.get("tags") or [],
        )


@dataclass(frozen=True)
class ReviewComment:
    file: str
    line: int
    severity: str
    comment: str
    confidence: float
    rule_key: Optional[str] = None
    code_suggestion: Optional[str] = None

    @staticmethod
    def from_json(obj: dict) -> "ReviewComment":
        return ReviewComment(
            file=obj.get("file", ""),
            line=int(obj.get("line", 0)),
            severity=obj.get("severity", "SUGGESTION"),
            comment=obj.get("comment", ""),
            confidence=float(obj.get("confidence") or 0.0),
            rule_key=obj.get("ruleKey"),
            code_suggestion=obj.get("codeSuggestion"),
        )


@dataclass(frozen=True)
class ReviewResult:
    """Everything the internal endpoint returns for one diff."""

    comments: list[ReviewComment]
    input_tokens: int = 0
    output_tokens: int = 0
    risk_level: Optional[str] = None
    risk_score: Optional[int] = None
    retrieved_snippets: int = 0

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens

    @staticmethod
    def from_json(body: dict) -> "ReviewResult":
        usage = body.get("usage") or {}
        risk = body.get("risk") or {}
        return ReviewResult(
            comments=[ReviewComment.from_json(c) for c in body.get("comments", [])],
            input_tokens=int(usage.get("inputTokens") or 0),
            output_tokens=int(usage.get("outputTokens") or 0),
            risk_level=risk.get("level"),
            risk_score=risk.get("score"),
            retrieved_snippets=int(body.get("retrievedSnippets") or 0),
        )


@dataclass
class CaseResult:
    case_id: str
    category: str
    true_positives: int = 0
    false_positives: int = 0
    false_negatives: int = 0
    must_not_flag_hits: int = 0
    cross_file: bool = False
    tokens: int = 0
    expected_risk: Optional[str] = None
    risk_level: Optional[str] = None
    comments: list[ReviewComment] = field(default_factory=list)

    @property
    def risk_correct(self) -> Optional[bool]:
        return None if self.expected_risk is None else self.expected_risk == self.risk_level


@dataclass
class Metrics:
    precision: float
    recall: float
    f1: float
    true_positives: int
    false_positives: int
    false_negatives: int
    cases: int
    cross_file_recall: float = 1.0
    must_not_flag_hits: int = 0
    risk_accuracy: float = 1.0
    mean_tokens_per_review: float = 0.0

    def to_json(self) -> dict:
        return {
            "precision": round(self.precision, 4),
            "recall": round(self.recall, 4),
            "f1": round(self.f1, 4),
            "true_positives": self.true_positives,
            "false_positives": self.false_positives,
            "false_negatives": self.false_negatives,
            "cases": self.cases,
            "cross_file_recall": round(self.cross_file_recall, 4),
            "must_not_flag_hits": self.must_not_flag_hits,
            "risk_accuracy": round(self.risk_accuracy, 4),
            "mean_tokens_per_review": round(self.mean_tokens_per_review, 1),
        }

    @staticmethod
    def compute(results: list[CaseResult]) -> "Metrics":
        tp = sum(r.true_positives for r in results)
        fp = sum(r.false_positives for r in results)
        fn = sum(r.false_negatives for r in results)
        precision = tp / (tp + fp) if (tp + fp) else 1.0
        recall = tp / (tp + fn) if (tp + fn) else 1.0
        f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0

        xf = [r for r in results if r.cross_file and (r.true_positives + r.false_negatives) > 0]
        xf_tp = sum(r.true_positives for r in xf)
        xf_total = sum(r.true_positives + r.false_negatives for r in xf)
        cross_file_recall = xf_tp / xf_total if xf_total else 1.0

        scored = [r for r in results if r.risk_correct is not None]
        risk_accuracy = sum(1 for r in scored if r.risk_correct) / len(scored) if scored else 1.0
        mean_tokens = sum(r.tokens for r in results) / len(results) if results else 0.0

        return Metrics(precision, recall, f1, tp, fp, fn, len(results),
                       cross_file_recall=cross_file_recall,
                       must_not_flag_hits=sum(r.must_not_flag_hits for r in results),
                       risk_accuracy=risk_accuracy,
                       mean_tokens_per_review=mean_tokens)
