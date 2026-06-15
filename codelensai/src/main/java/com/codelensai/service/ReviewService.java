package com.codelensai.service;

import com.codelensai.config.LlmProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.dto.ReviewStreamToken;
import com.codelensai.model.dto.ReviewToken;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.model.enums.Severity;
import com.codelensai.observability.LangfuseTracer;
import com.codelensai.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates a PR review: fetch diff -> chunk -> fan out chunks across virtual threads -> stream
 * tokens to WebSocket -> validate + persist. Chunk reviews are isolated (one bad chunk never loses
 * the others' comments). Idempotent on {@code (pr_id, head_sha)} via the persistence layer.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private final GitHubService gitHubService;
    private final DiffChunkerService chunker;
    private final AIReviewService aiReviewService;
    private final WebSocketNotifier notifier;
    private final ReviewPersistenceService persistence;
    private final PromptRepository prompts;
    private final LlmProperties llmProperties;
    private final MeterRegistry meterRegistry;
    private final LangfuseTracer langfuseTracer;

    public ReviewService(GitHubService gitHubService,
                         DiffChunkerService chunker,
                         AIReviewService aiReviewService,
                         WebSocketNotifier notifier,
                         ReviewPersistenceService persistence,
                         PromptRepository prompts,
                         LlmProperties llmProperties,
                         MeterRegistry meterRegistry,
                         LangfuseTracer langfuseTracer) {
        this.gitHubService = gitHubService;
        this.chunker = chunker;
        this.aiReviewService = aiReviewService;
        this.notifier = notifier;
        this.persistence = persistence;
        this.prompts = prompts;
        this.llmProperties = llmProperties;
        this.meterRegistry = meterRegistry;
        this.langfuseTracer = langfuseTracer;
    }

    /** Entry point invoked by {@link ReviewJobConsumer} for one queued job. */
    public void processReview(long prId, String headSha) {
        long t0 = System.currentTimeMillis();
        ReviewSession session;
        try {
            session = persistence.startOrReplaceSession(
                    prId, headSha, aiReviewService.providerId(), prompts.reviewVersion());
        } catch (DataIntegrityViolationException race) {
            // UNIQUE(pr_id, head_sha) lost a race — another worker is already reviewing this SHA.
            log.info("Review for prId={} sha={} already in progress (race); bailing", prId, headSha);
            return;
        }

        notifier.sendStatus(prId, ReviewStatus.IN_PROGRESS);

        String diff = gitHubService.fetchPrDiff(prId);
        List<DiffChunk> chunks = chunker.chunk(diff, llmProperties.maxTokensPerChunk());

        AtomicLong seq = new AtomicLong(0);
        Aggregate agg = reviewAllChunks(prId, chunks, seq);

        List<ReviewComment> comments = validateAndFilter(agg.comments(), chunks);
        int elapsed = (int) (System.currentTimeMillis() - t0);

        if (agg.allFailed() && !chunks.isEmpty()) {
            persistence.markFailed(prId, headSha, "all chunks failed", ReviewStatus.REVIEW_UNAVAILABLE);
            notifier.sendStatus(prId, ReviewStatus.REVIEW_UNAVAILABLE);
            return;
        }

        String summary = summarize(comments);
        persistence.completeReview(session.getId(), prId, comments, elapsed, agg.tokenCount(), summary);
        gitHubService.postReviewSummary(prId, summary);

        meterRegistry.timer("review.duration").record(Duration.ofMillis(elapsed));
        meterRegistry.counter("llm.tokens.total").increment(agg.tokenCount());
        langfuseTracer.traceReview(prId, headSha, aiReviewService.providerId(),
                prompts.reviewVersion(), agg.tokenCount(), elapsed, comments.size());
        notifier.sendStatus(prId, ReviewStatus.COMPLETED);
        log.info("Review complete prId={} comments={} elapsedMs={}", prId, comments.size(), elapsed);
    }

    /** Used by the internal eval endpoint — reviews a raw diff without streaming or persistence. */
    public List<ReviewComment> reviewDiff(String diff, String language) {
        List<DiffChunk> chunks = chunker.chunk(diff, llmProperties.maxTokensPerChunk());
        List<ReviewComment> all = new ArrayList<>();
        for (DiffChunk chunk : chunks) {
            all.addAll(assembleComments(collectTokens(chunk)));
        }
        return validateAndFilter(all, chunks);
    }

    public void markFailed(long prId, String headSha, String error) {
        persistence.markFailed(prId, headSha, error, ReviewStatus.REVIEW_FAILED);
        notifier.sendStatus(prId, ReviewStatus.REVIEW_FAILED);
    }

    private record Aggregate(List<ReviewComment> comments, int tokenCount, boolean allFailed) {
    }

    private Aggregate reviewAllChunks(long prId, List<DiffChunk> chunks, AtomicLong seq) {
        if (chunks.isEmpty()) {
            return new Aggregate(List.of(), 0, false);
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChunkOutcome>> futures = chunks.stream()
                    .map(chunk -> executor.submit(() -> reviewOne(prId, chunk, seq)))
                    .toList();

            List<ReviewComment> comments = new ArrayList<>();
            int tokenCount = 0;
            int failed = 0;
            for (Future<ChunkOutcome> f : futures) {
                ChunkOutcome outcome = join(f);
                comments.addAll(outcome.comments());
                tokenCount += outcome.tokenCount();
                if (outcome.failed()) {
                    failed++;
                }
            }
            return new Aggregate(comments, tokenCount, failed == chunks.size());
        }
    }

    private ChunkOutcome reviewOne(long prId, DiffChunk chunk, AtomicLong seq) {
        try {
            List<ReviewToken> tokens = collectTokens(chunk);
            for (ReviewToken t : tokens) {
                notifier.sendToken(prId, ReviewStreamToken.of(
                        seq.incrementAndGet(), t.file(), t.line(), t.severity(), t.text()));
            }
            return new ChunkOutcome(assembleComments(tokens), tokens.size(), false);
        } catch (Exception e) {
            log.warn("Chunk review failed for {}: {}", chunk.fileName(), e.getMessage());
            notifier.sendChunkError(prId, chunk.fileName());
            return new ChunkOutcome(List.of(), 0, true);
        }
    }

    private List<ReviewToken> collectTokens(DiffChunk chunk) {
        return aiReviewService.stream(chunk).toStream().toList();
    }

    private ChunkOutcome join(Future<ChunkOutcome> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            return new ChunkOutcome(List.of(), 0, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChunkOutcome(List.of(), 0, true);
        }
    }

    private record ChunkOutcome(List<ReviewComment> comments, int tokenCount, boolean failed) {
    }

    /** Reassemble streamed token fragments into one comment per (file, line, severity). */
    private List<ReviewComment> assembleComments(List<ReviewToken> tokens) {
        Map<String, StringBuilder> texts = new LinkedHashMap<>();
        Map<String, ReviewToken> anchors = new LinkedHashMap<>();
        for (ReviewToken t : tokens) {
            String key = t.file() + "\t" + t.line() + "\t" + t.severity();
            texts.computeIfAbsent(key, k -> new StringBuilder()).append(t.text());
            anchors.putIfAbsent(key, t);
        }
        List<ReviewComment> comments = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> e : texts.entrySet()) {
            ReviewToken a = anchors.get(e.getKey());
            comments.add(new ReviewComment(
                    a.file(), a.line(), a.severity(), e.getValue().toString().trim(),
                    null, confidenceFor(a.severity())));
        }
        return comments;
    }

    private List<ReviewComment> validateAndFilter(List<ReviewComment> comments, List<DiffChunk> chunks) {
        Set<String> anchors = computeValidAnchors(chunks);
        double threshold = llmProperties.confidenceThreshold();
        List<ReviewComment> kept = new ArrayList<>();
        for (ReviewComment c : comments) {
            if (c.confidence() < threshold) {
                continue;
            }
            if (!anchors.isEmpty() && !anchors.contains(c.filePath() + "\t" + c.lineNumber())) {
                log.debug("Dropping hallucinated comment at {}:{}", c.filePath(), c.lineNumber());
                continue;
            }
            kept.add(c);
        }
        return kept;
    }

    /** Real (file, line) anchors that exist as added/context lines in the diff hunks. */
    private Set<String> computeValidAnchors(List<DiffChunk> chunks) {
        Set<String> anchors = new java.util.HashSet<>();
        for (DiffChunk chunk : chunks) {
            int line = chunk.startLine() > 0 ? chunk.startLine() : 1;
            for (String raw : chunk.content().split("\n", -1)) {
                Matcher m = HUNK_HEADER.matcher(raw);
                if (m.matches()) {
                    line = Integer.parseInt(m.group(1));
                    continue;
                }
                if (raw.startsWith("+++") || raw.startsWith("---")) {
                    continue;
                }
                if (raw.startsWith("+")) {
                    anchors.add(chunk.fileName() + "\t" + line);
                    line++;
                } else if (raw.startsWith("-")) {
                    // removed: no new-file line
                } else {
                    anchors.add(chunk.fileName() + "\t" + line);
                    line++;
                }
            }
        }
        return anchors;
    }

    private String summarize(List<ReviewComment> comments) {
        long critical = comments.stream().filter(c -> c.severity() == Severity.CRITICAL).count();
        long warning = comments.stream().filter(c -> c.severity() == Severity.WARNING).count();
        long suggestion = comments.stream().filter(c -> c.severity() == Severity.SUGGESTION).count();
        return "%d comment(s): %d critical, %d warning, %d suggestion"
                .formatted(comments.size(), critical, warning, suggestion);
    }

    private double confidenceFor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 0.9;
            case WARNING -> 0.65;
            case SUGGESTION -> 0.45;
        };
    }
}
