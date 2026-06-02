# CodeLens AI — Starter Code
## (1) Webhook Idempotency [Spring Boot / Java]  +  (2) Review-Quality Eval Harness [Python]

> Drop-in starter implementations for the two highest-value pieces from the v2 plan. Both are written to be readable and adaptable, not framework-perfect — wire them into your package structure (§7 of the master plan).

---

# PART 1 — Webhook Idempotency (Spring Boot / Java 21)

**Goal:** GitHub delivers webhooks *at-least-once* — the same `pull_request` event can arrive 2–3 times. Without protection you run (and pay for) duplicate AI reviews and emit duplicate comments. Two layers:
1. **Delivery-level dedup** — reject a re-delivered webhook by its `X-GitHub-Delivery` UUID (Redis SETNX).
2. **Effect-level idempotency** — key reviews on `(pr_id, head_sha)` so even if a job runs twice, the result is upserted, not duplicated.

Plus HMAC-SHA256 signature verification with constant-time comparison.

---

### 1.1 `IdempotencyService.java` — Redis SETNX dedup

```java
package com.codelensai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redis;

    private static final Duration WEBHOOK_TTL = Duration.ofHours(24);
    private static final String WEBHOOK_KEY_PREFIX = "webhook:seen:";

    /**
     * Returns true if this delivery has NOT been seen before (i.e., we should process it).
     * Atomic: only the FIRST caller for a given deliveryId gets `true`.
     * Backed by Redis SET key value NX EX — a single atomic operation.
     */
    public boolean isFirstDelivery(String deliveryId) {
        Boolean wasSet = redis.opsForValue()
                .setIfAbsent(WEBHOOK_KEY_PREFIX + deliveryId, "1", WEBHOOK_TTL);
        return Boolean.TRUE.equals(wasSet);
    }
}
```

---

### 1.2 `WebhookSignatureValidator.java` — constant-time HMAC-SHA256

```java
package com.codelensai.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class WebhookSignatureValidator {

    @Value("${github.webhook-secret}")
    private String webhookSecret;

    private static final String ALGO = "HmacSHA256";

    /**
     * GitHub sends X-Hub-Signature-256: "sha256=<hex>".
     * We recompute HMAC-SHA256(secret, rawBody) and compare in constant time.
     */
    public boolean isValid(String rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String expectedHex = signatureHeader.substring("sha256=".length());
        String computedHex = hmacSha256Hex(rawBody);
        // Constant-time comparison prevents timing attacks
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                computedHex.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Hex(String body) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }
}
```

---

### 1.3 `WebhookController.java` — the idempotent receiver

```java
package com.codelensai.controller;

import com.codelensai.service.IdempotencyService;
import com.codelensai.service.WebhookService;
import com.codelensai.util.WebhookSignatureValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookSignatureValidator signatureValidator;
    private final IdempotencyService idempotency;
    private final WebhookService webhookService;

    @PostMapping("/github")
    public ResponseEntity<String> handleGithub(
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody) {

        // 1. Verify authenticity FIRST — reject forgeries before any work.
        if (!signatureValidator.isValid(rawBody, signature)) {
            log.warn("Rejected webhook: invalid signature (delivery={})", deliveryId);
            return ResponseEntity.status(401).body("invalid signature");
        }

        // 2. We only care about pull_request events here.
        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok("ignored event: " + eventType);
        }

        // 3. Idempotency: only the first delivery with this UUID proceeds.
        if (deliveryId == null) {
            return ResponseEntity.badRequest().body("missing X-GitHub-Delivery");
        }
        if (!idempotency.isFirstDelivery(deliveryId)) {
            log.info("Duplicate webhook ignored (delivery={})", deliveryId);
            return ResponseEntity.ok("duplicate ignored");   // idempotent no-op, HTTP 200
        }

        // 4. Parse + enqueue. We ACK fast (202) and process async via Redis Streams.
        webhookService.parseAndEnqueue(rawBody);
        return ResponseEntity.accepted().body("queued");
    }
}
```

---

### 1.4 `WebhookService.java` — parse + enqueue to Redis Streams

```java
package com.codelensai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final PullRequestService pullRequestService;

    private static final String STREAM_KEY = "review-jobs";

    public void parseAndEnqueue(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String action = root.path("action").asText();

            // Only review meaningful PR state changes.
            if (!Set.of("opened", "synchronize", "reopened").contains(action)) {
                return;
            }

            JsonNode pr = root.path("pull_request");
            long githubPrId = pr.path("id").asLong();
            int prNumber    = pr.path("number").asInt();
            String headSha  = pr.path("head").path("sha").asText();
            String title    = pr.path("title").asText();
            String repoFull = root.path("repository").path("full_name").asText();

            // Persist/refresh PR metadata, get our internal PR id.
            long prId = pullRequestService.upsertPullRequest(
                    repoFull, githubPrId, prNumber, title, headSha);

            // Enqueue the review job. head_sha makes the EFFECT idempotent downstream.
            Map<String, String> job = Map.of(
                    "prId", String.valueOf(prId),
                    "headSha", headSha);
            redis.opsForStream().add(StreamRecords.mapBacked(job).withStreamKey(STREAM_KEY));

            log.info("Enqueued review job prId={} headSha={}", prId, headSha.substring(0, 7));
        } catch (Exception e) {
            log.error("Failed to parse/enqueue webhook", e);
            throw new RuntimeException(e);
        }
    }
}
```

---

### 1.5 `ReviewJobConsumer.java` — idempotent consumer (effectively-once)

```java
package com.codelensai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Consumes review jobs from a Redis Stream with a consumer group.
 * Redis Streams give AT-LEAST-ONCE delivery (a worker can crash after read,
 * before ack). We make the EFFECT idempotent by keying reviews on
 * (pr_id, head_sha): at-least-once delivery + idempotent consumer = effectively-once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewJobConsumer {

    private final StringRedisTemplate redis;
    private final ReviewService reviewService;
    private final ReviewSessionRepository sessionRepo;

    private static final String STREAM = "review-jobs";
    private static final String GROUP = "reviewers";
    private static final String DLQ = "review-jobs-dlq";
    private static final int MAX_RETRIES = 3;

    private final String consumerName = "worker-" + System.getenv().getOrDefault("HOSTNAME", "1");

    /** Called on a loop by a scheduled executor / @Scheduled. */
    public void poll() {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5)),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) return;

        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> body = record.getValue();
            long prId = Long.parseLong(body.get("prId").toString());
            String headSha = body.get("headSha").toString();

            try {
                // EFFECT-LEVEL IDEMPOTENCY: skip if this (pr, sha) already reviewed.
                if (sessionRepo.existsByPrIdAndHeadSha(prId, headSha)) {
                    log.info("Skipping already-reviewed prId={} sha={}", prId, headSha.substring(0,7));
                    ack(record);
                    continue;
                }

                reviewService.processReview(prId, headSha);
                ack(record);                                  // ack only AFTER success

            } catch (Exception e) {
                handleFailure(record, prId, headSha, e);
            }
        }
    }

    private void ack(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
    }

    private void handleFailure(MapRecord<String, Object, Object> record,
                               long prId, String headSha, Exception e) {
        // Count deliveries for this message via XPENDING; route to DLQ after MAX_RETRIES.
        long deliveries = pendingDeliveryCount(record.getId());
        log.warn("Review failed prId={} attempt={} : {}", prId, deliveries, e.getMessage());

        if (deliveries >= MAX_RETRIES) {
            // Move to DLQ, ack the original so it stops being redelivered, mark PR FAILED.
            redis.opsForStream().add(StreamRecords.mapBacked(
                    Map.of("prId", String.valueOf(prId), "headSha", headSha,
                           "error", e.getMessage())).withStreamKey(DLQ));
            ack(record);
            reviewService.markFailed(prId, headSha, e.getMessage());
            log.error("Moved prId={} to DLQ after {} attempts", prId, deliveries);
        }
        // else: do NOT ack → Redis will redeliver to a consumer for retry.
    }

    private long pendingDeliveryCount(RecordId id) {
        PendingMessages pending = redis.opsForStream()
                .pending(STREAM, GROUP, Range.closed(id.getValue(), id.getValue()), 1L);
        return pending.isEmpty() ? 1 : pending.get(0).getTotalDeliveryCount();
    }
}
```

---

### 1.6 Idempotent upsert in the repository layer

```java
// ReviewSessionRepository.java (Spring Data JPA)
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {
    boolean existsByPrIdAndHeadSha(Long prId, String headSha);
    Optional<ReviewSession> findByPrIdAndHeadSha(Long prId, String headSha);
}
```

```java
// Inside ReviewService — create-or-replace the session for (prId, headSha)
@Transactional
public ReviewSession startOrReplaceSession(Long prId, String headSha, String model, String promptVersion) {
    sessionRepo.findByPrIdAndHeadSha(prId, headSha).ifPresent(existing -> {
        // Re-review of the same SHA: clear old comments, reuse the row.
        reviewCommentRepo.deleteBySessionId(existing.getId());
        sessionRepo.delete(existing);
        sessionRepo.flush();
    });
    ReviewSession s = new ReviewSession();
    s.setPrId(prId); s.setHeadSha(headSha);
    s.setModelUsed(model); s.setPromptVersion(promptVersion);
    s.setStatus("IN_PROGRESS");
    return sessionRepo.save(s);                     // UNIQUE(pr_id, head_sha) is the safety net
}
```

> The DB-level `UNIQUE(pr_id, head_sha)` constraint (in the schema, §8 of the master plan) is the ultimate backstop: even with a race between two workers, the second insert fails and that worker bails — the review can never be duplicated.

---

### 1.7 A quick test you can run (and demo!)

```java
@SpringBootTest
class WebhookIdempotencyTest {

    @Autowired IdempotencyService idempotency;

    @Test
    void duplicateDeliveryIsIgnored() {
        String deliveryId = "test-uuid-" + UUID.randomUUID();
        assertTrue(idempotency.isFirstDelivery(deliveryId));   // 1st: process
        assertFalse(idempotency.isFirstDelivery(deliveryId));  // 2nd: ignore
        assertFalse(idempotency.isFirstDelivery(deliveryId));  // 3rd: ignore
    }
}
```

> **Demo move:** in your video, re-deliver the same webhook from GitHub's "Recent Deliveries" panel and show the logs printing `Duplicate webhook ignored` and only ONE review running. That single moment proves you understand at-least-once delivery.

---

---

# PART 2 — Review-Quality Eval Harness (Python 3.12)

**Goal:** Answer *"how do you know the AI reviews are good?"* with a number, not vibes. We maintain a benchmark of diffs with **planted bugs** and measure how many the reviewer catches.

```
eval/
├── testset.jsonl          # planted-bug cases
├── eval_types.py          # dataclasses
├── reviewer_client.py     # calls YOUR review endpoint/service
├── run_eval.py            # runs cases, computes metrics, CI gate
├── requirements.txt
└── baseline.json          # last known-good metrics (committed; CI compares against this)
```

---

### 2.1 `testset.jsonl` — planted-bug benchmark (sample)

Each line is one case: a diff that contains a known bug, plus the expected finding.

```jsonl
{"id": "null-deref-001", "language": "java", "bug_class": "null_dereference", "expected": {"file": "UserService.java", "line": 42, "severity": "CRITICAL"}, "diff": "--- a/UserService.java\n+++ b/UserService.java\n@@ -40,3 +40,5 @@\n public String displayName(Long userId) {\n+    User u = repo.findById(userId);\n+    return u.getName().toUpperCase();   // u may be null if not found\n }"}
{"id": "sqli-001", "language": "java", "bug_class": "sql_injection", "expected": {"file": "SearchDao.java", "line": 18, "severity": "CRITICAL"}, "diff": "--- a/SearchDao.java\n+++ b/SearchDao.java\n@@ -16,2 +16,4 @@\n public List<Row> search(String q) {\n+    String sql = \"SELECT * FROM items WHERE name = '\" + q + \"'\";   // string-concatenated SQL\n+    return jdbc.query(sql, rowMapper);\n }"}
{"id": "race-001", "language": "java", "bug_class": "race_condition", "expected": {"file": "Counter.java", "line": 9, "severity": "WARNING"}, "diff": "--- a/Counter.java\n+++ b/Counter.java\n@@ -7,3 +7,4 @@\n private int count = 0;\n+public void increment() { count++; }   // non-atomic, shared mutable state, no sync\n+public int get() { return count; }"}
{"id": "offbyone-001", "language": "python", "bug_class": "off_by_one", "expected": {"file": "paginate.py", "line": 5, "severity": "WARNING"}, "diff": "--- a/paginate.py\n+++ b/paginate.py\n@@ -3,2 +3,4 @@\n def page(items, page_no, size):\n+    start = page_no * size\n+    return items[start : start + size + 1]   # off-by-one: includes one extra item"}
{"id": "secret-001", "language": "python", "bug_class": "hardcoded_secret", "expected": {"file": "config.py", "line": 3, "severity": "CRITICAL"}, "diff": "--- a/config.py\n+++ b/config.py\n@@ -1,2 +1,4 @@\n import os\n+API_KEY = \"sk-live-9f8a7b6c5d4e3f2a1b0c\"   # hardcoded secret committed to source\n+DB_URL = os.environ[\"DB_URL\"]"}
{"id": "missing-errhandling-001", "language": "java", "bug_class": "missing_error_handling", "expected": {"file": "FileLoader.java", "line": 12, "severity": "WARNING"}, "diff": "--- a/FileLoader.java\n+++ b/FileLoader.java\n@@ -10,2 +10,4 @@\n public String load(String path) {\n+    return Files.readString(Path.of(path));   // no try/catch; IOException swallowed by caller?\n }"}
{"id": "clean-001", "language": "java", "bug_class": "none", "expected": null, "diff": "--- a/Greeting.java\n+++ b/Greeting.java\n@@ -1,2 +1,4 @@\n public class Greeting {\n+    public String hello(String name) {\n+        return name == null ? \"Hello!\" : \"Hello, \" + name + \"!\";\n }"}
```

> Note the last case (`clean-001`, `bug_class: none`) — it's a **clean diff with no bug**. If the reviewer flags it, that's a false positive. You need clean cases to measure precision honestly.

---

### 2.2 `eval_types.py`

```python
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class ExpectedFinding:
    file: str
    line: int
    severity: str            # CRITICAL | WARNING | SUGGESTION


@dataclass
class EvalCase:
    id: str
    language: str
    bug_class: str           # e.g. "sql_injection", or "none" for clean diffs
    diff: str
    expected: Optional[ExpectedFinding]   # None for clean diffs


@dataclass
class ReviewComment:
    file: str
    line: int
    severity: str
    text: str = ""


@dataclass
class CaseResult:
    case_id: str
    bug_class: str
    is_clean_case: bool
    caught: bool             # did the reviewer flag the planted bug?
    severity_correct: bool   # did it use the expected severity?
    false_positives: int     # comments on a clean case, or far from the bug line


@dataclass
class EvalReport:
    results: list[CaseResult] = field(default_factory=list)

    @property
    def bug_cases(self) -> list[CaseResult]:
        return [r for r in self.results if not r.is_clean_case]

    @property
    def clean_cases(self) -> list[CaseResult]:
        return [r for r in self.results if r.is_clean_case]

    @property
    def bug_catch_rate(self) -> float:
        bugs = self.bug_cases
        return sum(1 for r in bugs if r.caught) / len(bugs) if bugs else 0.0

    @property
    def severity_accuracy(self) -> float:
        caught = [r for r in self.bug_cases if r.caught]
        return sum(1 for r in caught if r.severity_correct) / len(caught) if caught else 0.0

    @property
    def false_positive_rate(self) -> float:
        # FP rate = clean cases that got ANY comment / total clean cases
        clean = self.clean_cases
        return sum(1 for r in clean if r.false_positives > 0) / len(clean) if clean else 0.0
```

---

### 2.3 `reviewer_client.py` — plug in YOUR reviewer

```python
"""
Adapter to call your actual review pipeline. Two modes:
  • HTTP: call your running backend's review endpoint.
  • In-process: import and call your Python reviewer directly (if you have one).

Swap the implementation; the eval harness only depends on `review_diff()`.
"""
from __future__ import annotations
import os
import requests
from eval_types import ReviewComment

BACKEND_URL = os.environ.get("CODELENS_API", "http://localhost:8080")


def review_diff(diff: str, language: str) -> list[ReviewComment]:
    """
    Sends a diff to the review service and returns structured comments.
    Adjust the endpoint/payload to match your API.
    """
    resp = requests.post(
        f"{BACKEND_URL}/api/internal/review-diff",   # an internal eval-only endpoint
        json={"diff": diff, "language": language},
        timeout=120,
    )
    resp.raise_for_status()
    data = resp.json()
    return [
        ReviewComment(
            file=c["file"],
            line=int(c["line"]),
            severity=c["severity"].upper(),
            text=c.get("comment", ""),
        )
        for c in data.get("comments", [])
    ]
```

---

### 2.4 `run_eval.py` — runs the benchmark, computes metrics, CI gate

```python
#!/usr/bin/env python3
"""
Review-quality eval harness.

Usage:
  python run_eval.py                 # run + print report
  python run_eval.py --ci            # also compare to baseline.json, exit 1 on regression
  python run_eval.py --save-baseline # write current metrics as the new baseline
"""
from __future__ import annotations
import argparse
import json
import sys
from pathlib import Path

from eval_types import EvalCase, ExpectedFinding, ReviewComment, CaseResult, EvalReport
from reviewer_client import review_diff

HERE = Path(__file__).parent
TESTSET = HERE / "testset.jsonl"
BASELINE = HERE / "baseline.json"

# A flagged comment counts as catching the bug if it's on the right file
# and within this many lines of the expected line (diffs shift line numbers).
LINE_TOLERANCE = 3

# Regression thresholds for the CI gate.
MAX_CATCH_RATE_DROP = 0.05      # fail if bug-catch rate falls >5 percentage points
MAX_FP_RATE_RISE = 0.05         # fail if false-positive rate rises >5 percentage points


def load_cases() -> list[EvalCase]:
    cases = []
    for line in TESTSET.read_text().splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        exp = d.get("expected")
        cases.append(EvalCase(
            id=d["id"], language=d["language"], bug_class=d["bug_class"],
            diff=d["diff"],
            expected=ExpectedFinding(**exp) if exp else None,
        ))
    return cases


def matches(comment: ReviewComment, expected: ExpectedFinding) -> bool:
    same_file = Path(comment.file).name == Path(expected.file).name
    near_line = abs(comment.line - expected.line) <= LINE_TOLERANCE
    return same_file and near_line


def evaluate_case(case: EvalCase, comments: list[ReviewComment]) -> CaseResult:
    is_clean = case.expected is None

    if is_clean:
        # Any comment on a clean diff is a false positive.
        return CaseResult(
            case_id=case.id, bug_class=case.bug_class, is_clean_case=True,
            caught=False, severity_correct=False, false_positives=len(comments),
        )

    caught = any(matches(c, case.expected) for c in comments)
    severity_correct = any(
        matches(c, case.expected) and c.severity == case.expected.severity
        for c in comments
    )
    # FP on a bug case = comments that are NOT near the planted bug.
    fps = sum(1 for c in comments if not matches(c, case.expected))
    return CaseResult(
        case_id=case.id, bug_class=case.bug_class, is_clean_case=False,
        caught=caught, severity_correct=severity_correct, false_positives=fps,
    )


def run() -> EvalReport:
    report = EvalReport()
    for case in load_cases():
        try:
            comments = review_diff(case.diff, case.language)
        except Exception as e:
            print(f"  [{case.id}] reviewer ERROR: {e}", file=sys.stderr)
            comments = []
        result = evaluate_case(case, comments)
        report.results.append(result)
        flag = "✓" if (result.caught or result.is_clean_case and result.false_positives == 0) else "✗"
        print(f"  {flag} {case.id:24s} bug={case.bug_class:20s} "
              f"caught={result.caught} sev_ok={result.severity_correct} fp={result.false_positives}")
    return report


def print_report(report: EvalReport) -> dict:
    metrics = {
        "bug_catch_rate": round(report.bug_catch_rate, 4),
        "severity_accuracy": round(report.severity_accuracy, 4),
        "false_positive_rate": round(report.false_positive_rate, 4),
        "n_bug_cases": len(report.bug_cases),
        "n_clean_cases": len(report.clean_cases),
    }
    print("\n" + "=" * 50)
    print("REVIEW-QUALITY EVAL REPORT")
    print("=" * 50)
    print(f"  Bug-catch rate (recall):   {metrics['bug_catch_rate']:.0%}  "
          f"({sum(1 for r in report.bug_cases if r.caught)}/{metrics['n_bug_cases']})")
    print(f"  Severity accuracy:         {metrics['severity_accuracy']:.0%}")
    print(f"  False-positive rate:       {metrics['false_positive_rate']:.0%}  "
          f"(on {metrics['n_clean_cases']} clean cases)")
    print("=" * 50)
    return metrics


def ci_gate(metrics: dict) -> int:
    if not BASELINE.exists():
        print("\nNo baseline.json found — run with --save-baseline first.")
        return 0
    base = json.loads(BASELINE.read_text())
    catch_drop = base["bug_catch_rate"] - metrics["bug_catch_rate"]
    fp_rise = metrics["false_positive_rate"] - base["false_positive_rate"]

    print(f"\nCI GATE (vs baseline):")
    print(f"  catch-rate change: {-catch_drop:+.2%} (allowed drop ≤ {MAX_CATCH_RATE_DROP:.0%})")
    print(f"  fp-rate change:    {fp_rise:+.2%} (allowed rise ≤ {MAX_FP_RATE_RISE:.0%})")

    failed = False
    if catch_drop > MAX_CATCH_RATE_DROP:
        print("  ❌ FAIL: bug-catch rate regressed too much")
        failed = True
    if fp_rise > MAX_FP_RATE_RISE:
        print("  ❌ FAIL: false-positive rate rose too much")
        failed = True
    if not failed:
        print("  ✅ PASS")
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ci", action="store_true", help="compare to baseline, exit 1 on regression")
    ap.add_argument("--save-baseline", action="store_true", help="write current metrics as baseline")
    args = ap.parse_args()

    print("Running review-quality eval...\n")
    report = run()
    metrics = print_report(report)

    if args.save_baseline:
        BASELINE.write_text(json.dumps(metrics, indent=2))
        print(f"\nSaved baseline → {BASELINE}")
        return 0
    if args.ci:
        return ci_gate(metrics)
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

---

### 2.5 `requirements.txt`

```
requests>=2.31
```

(That's it — the harness is intentionally dependency-light. Your reviewer client may add the LLM SDK if you run in-process instead of over HTTP.)

---

### 2.6 `baseline.json` (example — generated by `--save-baseline`)

```json
{
  "bug_catch_rate": 0.85,
  "severity_accuracy": 0.78,
  "false_positive_rate": 0.12,
  "n_bug_cases": 6,
  "n_clean_cases": 1
}
```

---

### 2.7 Wiring it into CI (matches §16 of the master plan)

```yaml
  review-quality-eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - name: Run review-quality eval
        run: |
          cd eval
          pip install -r requirements.txt
          python run_eval.py --ci          # exits 1 → fails the build on regression
        env:
          CODELENS_API: ${{ secrets.STAGING_API_URL }}
          LLM_API_KEY:  ${{ secrets.LLM_API_KEY }}
```

---

## How to talk about this code in an interview

**Webhook idempotency:** *"GitHub delivers webhooks at-least-once, so I dedup on the X-GitHub-Delivery UUID with Redis SETNX, and I make the effect idempotent by keying review sessions on (pr_id, head_sha) with a UNIQUE constraint. At-least-once delivery plus an idempotent consumer gives effectively-once processing — and I ack the Redis Stream message only after the review succeeds, so a crash mid-review just redelivers. After three failures the job goes to a dead-letter stream and the PR is marked failed with a retry button."*

**Eval harness:** *"I don't claim my reviews are good — I measure it. I keep ~30 diffs with planted bugs plus clean diffs, and compute bug-catch rate, severity accuracy, and false-positive rate. It runs in CI against a committed baseline and fails the build if catch rate drops more than 5 points or false positives rise more than 5 points. So a prompt or model change that looks fine but quietly got dumber can't merge."*

Those two answers, backed by code you actually wrote, put you ahead of almost every other junior candidate.
