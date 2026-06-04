"""Shared types for the CodeLens AI review-quality eval harness.

The harness measures "how good are the reviews?" against a fixed test set of diffs with planted bugs
(and clean controls). It calls the backend's internal eval endpoint and scores precision/recall on
whether the planted lines were flagged. With the local (mock) provider this is fully deterministic and
runs offline, which is what makes it usable as a CI gate.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

SEVERITY_RANK = {"SUGGESTION": 1, "WARNING": 2, "CRITICAL": 3}


@dataclass(frozen=True)
class ExpectedFinding:
    """A planted bug we expect the reviewer to flag."""

    line: int
    min_severity: str = "SUGGESTION"

    def satisfied_by(self, severity: str) -> bool:
        return SEVERITY_RANK.get(severity, 0) >= SEVERITY_RANK.get(self.min_severity, 0)


@dataclass(frozen=True)
class TestCase:
    id: str
    diff: str
    language: str
    category: str
    expected_findings: list[ExpectedFinding]

    @property
    def is_clean(self) -> bool:
        return len(self.expected_findings) == 0

    @staticmethod
    def from_json(obj: dict) -> "TestCase":
        return TestCase(
            id=obj["id"],
            diff=obj["diff"],
            language=obj.get("language", "java"),
            category=obj.get("category", "uncategorized"),
            expected_findings=[
                ExpectedFinding(line=f["line"], min_severity=f.get("min_severity", "SUGGESTION"))
                for f in obj.get("expected_findings", [])
            ],
        )


@dataclass(frozen=True)
class ReviewComment:
    file: str
    line: int
    severity: str
    comment: str
    confidence: float

    @staticmethod
    def from_json(obj: dict) -> "ReviewComment":
        return ReviewComment(
            file=obj.get("file", ""),
            line=int(obj.get("line", 0)),
            severity=obj.get("severity", "SUGGESTION"),
            comment=obj.get("comment", ""),
            confidence=float(obj.get("confidence", 0.0)),
        )


@dataclass
class CaseResult:
    case_id: str
    category: str
    true_positives: int = 0
    false_positives: int = 0
    false_negatives: int = 0
    comments: list[ReviewComment] = field(default_factory=list)


@dataclass
class Metrics:
    precision: float
    recall: float
    f1: float
    true_positives: int
    false_positives: int
    false_negatives: int
    cases: int

    def to_json(self) -> dict:
        return {
            "precision": round(self.precision, 4),
            "recall": round(self.recall, 4),
            "f1": round(self.f1, 4),
            "true_positives": self.true_positives,
            "false_positives": self.false_positives,
            "false_negatives": self.false_negatives,
            "cases": self.cases,
        }

    @staticmethod
    def compute(results: list[CaseResult]) -> "Metrics":
        tp = sum(r.true_positives for r in results)
        fp = sum(r.false_positives for r in results)
        fn = sum(r.false_negatives for r in results)
        precision = tp / (tp + fp) if (tp + fp) else 1.0
        recall = tp / (tp + fn) if (tp + fn) else 1.0
        f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
        return Metrics(precision, recall, f1, tp, fp, fn, len(results))
