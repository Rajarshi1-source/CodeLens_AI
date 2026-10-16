"""Keeps the Claude skills (.claude/skills/codelens-skills) consistent with the code and the ADRs.

Fails (exit 1) when:
  1. a skill document contains guidance we deliberately retired (stateless/JWT security, ReviewToken,
     vector(1536)/IVFFlat, a migration framework, "pgvector post-MVP", the AST "stretch goal",
     react-diff-viewer-continued, token.prId());
  2. the two copies of build-and-run.md differ (repo root vs. the Spring skill reference);
  3. a REST or STOMP endpoint mapped in a controller is missing from the Spring skill's API surface;
  4. an evals.json is malformed (bad JSON, duplicate ids, missing prompt/expected_output/assertions).

Run:  python scripts/check_skill_consistency.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / ".claude" / "skills" / "codelens-skills"
JAVA = ROOT / "codelensai" / "src" / "main" / "java" / "com" / "codelensai"

# Retired guidance, matched in skill *documents* (SKILL.md + references). Evals may mention these in negations
# ("does NOT set STATELESS"), so they are not scanned.
STALE = [
    (re.compile(r"SessionCreationPolicy\.STATELESS\)"), "stateless session policy (sessions are used; ADR 0004/0006)"),
    (re.compile(r"oauth2ResourceServer\(\s*o\s*->\s*o\.jwt"), "JWT resource server wiring (deferred; ADR 0004/0006)"),
    (re.compile(r"\bReviewToken\b"), "ReviewToken (replaced by the sealed ReviewEvent; ADR 0009)"),
    (re.compile(r"vector\(1536\)"), "vector(1536) (the schema is vector(512))"),
    (re.compile(r"USING ivfflat", re.IGNORECASE), "IVFFlat index (HNSW is used)"),
    (re.compile(r"Use Flyway|Flyway or Liquibase"), "a migration framework (schema.sql is idempotent)"),
    (re.compile(r"post-MVP add|pgvector post-MVP", re.IGNORECASE), "pgvector as post-MVP (shipped in G1)"),
    (re.compile(r"stretch goal", re.IGNORECASE), "structure-aware chunking as a stretch goal (shipped in G4)"),
    (re.compile(r"react-diff-viewer-continued"), "react-diff-viewer-continued (replaced by DiffFileTable; ADR 0007)"),
    (re.compile(r"token\.prId\(\)"), "token.prId() (the PR id comes from the Redis channel)"),
]

MAPPING = re.compile(
    r"@(Get|Post|Put|Delete|Patch|Subscribe|Message)Mapping(?:\((?:value\s*=\s*)?\"([^\"]*)\"[^)]*\)|\(([^)\"]*)\))?")
CLASS_PREFIX = re.compile(r"@RequestMapping\(\"([^\"]*)\"\)")


def skill_documents() -> list[Path]:
    return sorted(p for p in SKILLS.rglob("*.md"))


def check_stale(errors: list[str]) -> None:
    for doc in skill_documents():
        text = doc.read_text(encoding="utf-8")
        for pattern, why in STALE:
            for m in pattern.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                errors.append(f"{doc.relative_to(ROOT)}:{line}: stale guidance - {why}")


def normalized(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def check_build_and_run(errors: list[str]) -> None:
    a = ROOT / "build-and-run.md"
    b = SKILLS / "codelens-spring-boot" / "references" / "build-and-run.md"
    if not a.exists() or not b.exists():
        errors.append("build-and-run.md: one of the two copies is missing")
    elif normalized(a) != normalized(b):
        errors.append("build-and-run.md: the repo-root copy and the Spring skill reference differ - keep them identical")


def canonical(path: str) -> str:
    path = re.sub(r"\{[^}]+\}", "{id}", path)
    return path.rstrip("/") or "/"


def controller_endpoints() -> set[str]:
    """'GET /api/prs/{id}' for REST, 'STOMP /app/pr/{id}/replay' for @Subscribe/@MessageMapping."""
    found: set[str] = set()
    for src in JAVA.rglob("*.java"):
        text = src.read_text(encoding="utf-8")
        if "Mapping(" not in text or "@interface" in text:
            continue
        cls = CLASS_PREFIX.search(text)
        prefix = cls.group(1) if cls else ""
        for m in MAPPING.finditer(text):
            kind, path = m.group(1), m.group(2) or ""
            if kind in ("Subscribe", "Message"):
                found.add("STOMP " + canonical("/app" + path))
            else:
                found.add(f"{kind.upper()} " + canonical(prefix + path))
    return found


def documented_endpoints() -> set[str]:
    text = (SKILLS / "codelens-spring-boot" / "SKILL.md").read_text(encoding="utf-8")
    m = re.search(r"## API surface.*?```(.*?)```", text, re.S)
    if not m:
        return set()
    block = m.group(1)
    out: set[str] = set()
    for verb, path in re.findall(r"\b(GET|POST|PUT|DELETE|PATCH)\s+(/[^\s·]+)", block):
        path = re.sub(r"\[.*?\]|\?.*$", "", path)
        out.add(f"{verb} " + canonical(path))
    for path in re.findall(r"(/app/[^\s·]+)", block):
        out.add("STOMP " + canonical(path))
    return out


def check_api_surface(errors: list[str]) -> None:
    documented = documented_endpoints()
    if not documented:
        errors.append("codelens-spring-boot/SKILL.md: no '## API surface' code block found")
        return
    for endpoint in sorted(controller_endpoints() - documented):
        errors.append(f"codelens-spring-boot/SKILL.md: API surface is missing `{endpoint}` (mapped in a controller)")


def check_evals(errors: list[str]) -> None:
    for path in sorted(SKILLS.rglob("evals.json")):
        rel = path.relative_to(ROOT)
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            errors.append(f"{rel}: invalid JSON ({e})")
            continue
        evals = data.get("evals")
        if not isinstance(evals, list) or not evals:
            errors.append(f"{rel}: 'evals' must be a non-empty list")
            continue
        ids = [e.get("id") for e in evals]
        if len(ids) != len(set(ids)):
            errors.append(f"{rel}: duplicate eval ids {sorted(i for i in set(ids) if ids.count(i) > 1)}")
        for e in evals:
            for key in ("prompt", "expected_output"):
                if not isinstance(e.get(key), str) or not e[key].strip():
                    errors.append(f"{rel}: eval {e.get('id')} is missing '{key}'")
            assertions = e.get("assertions")
            if not isinstance(assertions, list) or not assertions:
                errors.append(f"{rel}: eval {e.get('id')} has no assertions")
            else:
                for a in assertions:
                    if not a.get("text") or a.get("type") not in ("behavioral", "contains", "absent"):
                        errors.append(f"{rel}: eval {e.get('id')} has a malformed assertion: {a}")


def main() -> int:
    errors: list[str] = []
    check_stale(errors)
    check_build_and_run(errors)
    check_api_surface(errors)
    check_evals(errors)
    if errors:
        print("Skill consistency check FAILED:")
        for e in errors:
            print("  - " + e)
        return 1
    n_endpoints = len(controller_endpoints())
    print(f"Skill consistency OK: {len(skill_documents())} documents, {n_endpoints} endpoints documented, "
          f"build-and-run copies identical, evals valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
