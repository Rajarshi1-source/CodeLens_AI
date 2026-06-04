"""Thin HTTP client for the backend's internal review endpoint (POST /api/internal/review-diff).

Mirrors ReviewDiffRequest {diff, language} -> ReviewDiffResponse {comments:[{file,line,severity,
comment,confidence}]}. The endpoint lives behind /api/internal/** which SecurityConfig permits, so the
harness needs no auth.
"""

from __future__ import annotations

import requests

from eval_types import ReviewComment


class ReviewerClient:
    def __init__(self, base_url: str, timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def review_diff(self, diff: str, language: str) -> list[ReviewComment]:
        resp = requests.post(
            f"{self.base_url}/api/internal/review-diff",
            json={"diff": diff, "language": language},
            timeout=self.timeout,
        )
        resp.raise_for_status()
        body = resp.json()
        return [ReviewComment.from_json(c) for c in body.get("comments", [])]

    def health(self) -> bool:
        try:
            resp = requests.get(f"{self.base_url}/actuator/health", timeout=self.timeout)
            return resp.status_code == 200
        except requests.RequestException:
            return False
