"""Thin HTTP client for the backend's internal review endpoint (POST /api/internal/review-diff).

Mirrors ReviewDiffRequest {diff, language, contextFiles, retrieval} -> ReviewDiffResponse {comments, usage,
risk, retrievedSnippets}. /api/internal/** exists only when codelens.internal.enabled=true; when the backend
sets codelens.internal.token, pass the same value via CODELENS_INTERNAL_TOKEN (sent as X-Internal-Token).
"""

from __future__ import annotations

import os

import requests

from eval_types import ReviewResult


class ReviewerClient:
    def __init__(self, base_url: str, timeout: float = 60.0, token: str | None = None):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token = token if token is not None else os.environ.get("CODELENS_INTERNAL_TOKEN")

    def _headers(self) -> dict[str, str]:
        return {"X-Internal-Token": self.token} if self.token else {}

    def review_diff(self, diff: str, language: str, context_files: dict[str, str] | None = None,
                    retrieval: bool = True) -> ReviewResult:
        payload = {"diff": diff, "language": language, "retrieval": retrieval}
        if context_files and retrieval:
            payload["contextFiles"] = context_files
        resp = requests.post(f"{self.base_url}/api/internal/review-diff", json=payload,
                             headers=self._headers(), timeout=self.timeout)
        if resp.status_code == 404:
            raise RuntimeError("POST /api/internal/review-diff returned 404 — start the backend with "
                               "CODELENS_INTERNAL_ENABLED=true (and the matching CODELENS_INTERNAL_TOKEN)")
        resp.raise_for_status()
        return ReviewResult.from_json(resp.json())

    def export_negatives(self, repo_id: int | None = None, min_dismissals: int = 2) -> str:
        params: dict[str, object] = {"minDismissals": min_dismissals}
        if repo_id is not None:
            params["repoId"] = repo_id
        resp = requests.get(f"{self.base_url}/api/internal/feedback/export", params=params,
                            headers=self._headers(), timeout=self.timeout)
        resp.raise_for_status()
        return resp.text

    def health(self) -> bool:
        try:
            resp = requests.get(f"{self.base_url}/actuator/health", timeout=self.timeout)
            return resp.status_code == 200
        except requests.RequestException:
            return False
