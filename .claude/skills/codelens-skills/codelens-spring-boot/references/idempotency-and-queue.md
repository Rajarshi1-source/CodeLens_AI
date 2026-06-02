# CodeLens AI — Idempotency & Redis Streams Queue Reference

Complete implementation of the at-least-once → effectively-once pipeline: HMAC verification, delivery-level dedup, the Stream producer, the idempotent consumer with DLQ, and the create-or-replace upsert. Load when touching the webhook receiver, the review-job consumer, or anything about duplicate handling. SKILL.md has the summary; this is the full code.

## Contents
1. The two-layer model
2. HMAC signature validation (constant-time)
3. Delivery-level dedup (Redis SETNX)
4. Producer — XADD to the Stream
5. Consumer — consumer group, ack-after-success, DLQ
6. Effect-level idempotency — create-or-replace session
7. Read-your-writes
8. Resumable WebSocket stream

## 1. The two-layer model

GitHub delivers webhooks at-least-once; Redis Streams delivers at-least-once. Two layers make the *effect* exactly-once:

1. **Delivery-level dedup** — reject a re-delivered webhook by its `X-GitHub-Delivery` UUID (Redis SETNX, 24h TTL). Cheap first filter.
2. **Effect-level idempotency** — key review sessions on `(pr_id, head_sha)` with a DB `UNIQUE` constraint. Even if a job runs twice (worker crash before ack), the result is replaced, not duplicated.

> at-least-once delivery + idempotent consumer = effectively-once.

## 2. HMAC signature validation (constant-time)

```java
@Component
public class WebhookSignatureValidator {
    @Value("${github.webhook-secret}") private String webhookSecret;
    private static final String ALGO = "HmacSHA256";

    public boolean isValid(String rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256="))
            return false;
        String expectedHex = signatureHeader.substring("sha256=".length());
        String computedHex = hmacSha256Hex(rawBody);
        return MessageDigest.isEqual(                       // constant-time
            expectedHex.getBytes(StandardCharsets.UTF_8),
            computedHex.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Hex(String body) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), ALGO));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Failed to compute HMAC", e); }
    }
}
```

Constant-time comparison (`MessageDigest.isEqual`) avoids leaking byte-match progress to a timing attacker. Verify the signature against the **raw body** before any parsing.

## 3. Delivery-level dedup (Redis SETNX)

```java
@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private final StringRedisTemplate redis;
    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "webhook:seen:";

    /** True only for the FIRST caller for this deliveryId (atomic SET NX EX). */
    public boolean isFirstDelivery(String deliveryId) {
        Boolean set = redis.opsForValue().setIfAbsent(PREFIX + deliveryId, "1", TTL);
        return Boolean.TRUE.equals(set);
    }
}
```

## 4. Producer — XADD to the Stream

```java
public void parseAndEnqueue(String rawBody) {
    JsonNode root = objectMapper.readTree(rawBody);
    String action = root.path("action").asText();
    if (!Set.of("opened", "synchronize", "reopened").contains(action)) return;

    JsonNode pr = root.path("pull_request");
    long githubPrId = pr.path("id").asLong();
    int  prNumber   = pr.path("number").asInt();
    String headSha  = pr.path("head").path("sha").asText();
    String title    = pr.path("title").asText();
    String repoFull = root.path("repository").path("full_name").asText();

    long prId = pullRequestService.upsertPullRequest(repoFull, githubPrId, prNumber, title, headSha);

    Map<String,String> job = Map.of("prId", String.valueOf(prId), "headSha", headSha);
    redis.opsForStream().add(StreamRecords.mapBacked(job).withStreamKey("review-jobs"));
}
```

`head_sha` rides along so the *effect* downstream is idempotent.

## 5. Consumer — consumer group, ack-after-success, DLQ

```java
@Slf4j @Service @RequiredArgsConstructor
public class ReviewJobConsumer {
    private final StringRedisTemplate redis;
    private final ReviewService reviewService;
    private final ReviewSessionRepository sessionRepo;

    private static final String STREAM = "review-jobs";
    private static final String GROUP  = "reviewers";
    private static final String DLQ    = "review-jobs-dlq";
    private static final int MAX_RETRIES = 3;
    private final String consumer = "worker-" + System.getenv().getOrDefault("HOSTNAME", "1");

    public void poll() {
        var records = redis.opsForStream().read(
            Consumer.from(GROUP, consumer),
            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5)),
            StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) return;

        for (var record : records) {
            long prId = Long.parseLong(record.getValue().get("prId").toString());
            String headSha = record.getValue().get("headSha").toString();
            try {
                if (sessionRepo.existsByPrIdAndHeadSha(prId, headSha)) { ack(record); continue; }
                reviewService.processReview(prId, headSha);
                ack(record);                                  // ack ONLY after success
            } catch (Exception e) {
                handleFailure(record, prId, headSha, e);
            }
        }
    }

    private void ack(MapRecord<String,Object,Object> r) {
        redis.opsForStream().acknowledge(STREAM, GROUP, r.getId());
    }

    private void handleFailure(MapRecord<String,Object,Object> r, long prId, String sha, Exception e) {
        long deliveries = pendingDeliveryCount(r.getId());
        if (deliveries >= MAX_RETRIES) {
            redis.opsForStream().add(StreamRecords.mapBacked(
                Map.of("prId", String.valueOf(prId), "headSha", sha, "error", e.getMessage()))
                .withStreamKey(DLQ));
            ack(r);                                           // stop redelivery
            reviewService.markFailed(prId, sha, e.getMessage());   // PR → REVIEW_FAILED + retry button
        }
        // else: do NOT ack → Redis redelivers for retry
    }

    private long pendingDeliveryCount(RecordId id) {
        PendingMessages p = redis.opsForStream()
            .pending(STREAM, GROUP, Range.closed(id.getValue(), id.getValue()), 1L);
        return p.isEmpty() ? 1 : p.get(0).getTotalDeliveryCount();
    }
}
```

The group must be created once at startup (`XGROUP CREATE review-jobs reviewers $ MKSTREAM`); guard for "BUSYGROUP" on restart.

## 6. Effect-level idempotency — create-or-replace session

```java
@Transactional
public ReviewSession startOrReplaceSession(Long prId, String headSha, String model, String promptVersion) {
    sessionRepo.findByPrIdAndHeadSha(prId, headSha).ifPresent(existing -> {
        reviewCommentRepo.deleteBySessionId(existing.getId());   // re-review same SHA: clear old
        sessionRepo.delete(existing);
        sessionRepo.flush();
    });
    ReviewSession s = new ReviewSession();
    s.setPrId(prId); s.setHeadSha(headSha);
    s.setModelUsed(model); s.setPromptVersion(promptVersion);
    s.setStatus("IN_PROGRESS");
    return sessionRepo.save(s);     // UNIQUE(pr_id, head_sha) is the race backstop
}
```

If two workers race, the second `save` throws `DataIntegrityViolationException` — catch it, log, and bail; the review is already being produced by the winner.

## 7. Read-your-writes

Dashboard reads hit the Postgres read replica (eventual, ms lag). The user who just triggered a re-review must see their result, so route *that* user's reads to the primary for a short window after their write. Otherwise they click "re-review" and see no change, which reads as a bug.

## 8. Resumable WebSocket stream

Each review session numbers its comments with a monotonic `seq`. On reconnect:

1. Client sends its last-seen `seq` for the PR.
2. Server replays comments with `seq >` that value from the Redis Stream (or Postgres).
3. Server resumes live push.

This turns the AP live stream into a guarantee that the reviewer *eventually* sees every comment despite a dropped connection or a pod restart.
