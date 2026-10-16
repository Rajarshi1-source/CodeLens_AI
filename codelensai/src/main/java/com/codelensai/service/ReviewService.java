package com.codelensai.service;

import com.codelensai.config.LlmProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewComment;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewStreamToken;
import com.codelensai.model.dto.ReviewTopicMessage;
import com.codelensai.model.dto.SummaryResult;
import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.observability.LangfuseTracer;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.service.code.FileContentProvider;
import com.codelensai.service.cost.BudgetExceededException;
import com.codelensai.service.cost.ModelPricing;
import com.codelensai.service.feedback.SuppressionService;
import com.codelensai.service.github.HeadContentsService;
import com.codelensai.service.retrieval.ContextRetrievalService;
import com.codelensai.service.retrieval.ContextSource;
import com.codelensai.service.retrieval.RetrievedContext;
import com.codelensai.service.risk.ReviewCommentFormatter;
import com.codelensai.service.risk.ReviewSummaryService;
import com.codelensai.service.risk.RiskAssessment;
import com.codelensai.service.risk.RiskScoringService;
import com.codelensai.util.DiffParser;
import com.codelensai.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Orchestrates a PR review:
 * <ol>
 *   <li>snapshot the diff, chunk it along code structure (G4);</li>
 *   <li>retrieve repo context per chunk — callers, tests, similar code (G1);</li>
 *   <li>fan chunks out across virtual threads and stream each finding to the browser <b>as it arrives</b>
 *       (Redis Pub/Sub → STOMP, buffered for resume);</li>
 *   <li>validate anchors, apply the confidence floor and feedback suppression (G7);</li>
 *   <li>score risk deterministically and summarize (G3); account tokens and cost;</li>
 *   <li>persist everything in one transaction and post the summary to GitHub.</li>
 * </ol>
 * Chunk reviews are isolated (one bad chunk never loses the others' comments). Idempotent on
 * {@code (pr_id, head_sha)} via the persistence layer.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    private static final Duration CHUNK_TIMEOUT = Duration.ofMinutes(5);

    private final PullRequestRepository pullRequestRepository;
    private final DiffSnapshotService diffSnapshots;
    private final HeadContentsService headContents;
    private final DiffChunkerService chunker;
    private final DiffParser diffParser;
    private final ContextRetrievalService retrieval;
    private final AIReviewService aiReviewService;
    private final WebSocketNotifier notifier;
    private final ReviewPersistenceService persistence;
    private final PromptRepository prompts;
    private final LlmProperties llmProperties;
    private final SuppressionService suppression;
    private final RiskScoringService riskScoring;
    private final ReviewSummaryService summaries;
    private final ModelPricing pricing;
    private final GitHubService gitHubService;
    private final MeterRegistry meterRegistry;
    private final LangfuseTracer langfuseTracer;

    public ReviewService(PullRequestRepository pullRequestRepository, DiffSnapshotService diffSnapshots,
                         HeadContentsService headContents, DiffChunkerService chunker, DiffParser diffParser,
                         ContextRetrievalService retrieval, AIReviewService aiReviewService,
                         WebSocketNotifier notifier, ReviewPersistenceService persistence, PromptRepository prompts,
                         LlmProperties llmProperties, SuppressionService suppression, RiskScoringService riskScoring,
                         ReviewSummaryService summaries, ModelPricing pricing, GitHubService gitHubService,
                         MeterRegistry meterRegistry, LangfuseTracer langfuseTracer) {
        this.pullRequestRepository = pullRequestRepository;
        this.diffSnapshots = diffSnapshots;
        this.headContents = headContents;
        this.chunker = chunker;
        this.diffParser = diffParser;
        this.retrieval = retrieval;
        this.aiReviewService = aiReviewService;
        this.notifier = notifier;
        this.persistence = persistence;
        this.prompts = prompts;
        this.llmProperties = llmProperties;
        this.suppression = suppression;
        this.riskScoring = riskScoring;
        this.summaries = summaries;
        this.pricing = pricing;
        this.gitHubService = gitHubService;
        this.meterRegistry = meterRegistry;
        this.langfuseTracer = langfuseTracer;
    }

    /** Entry point invoked by {@link ReviewJobConsumer} for one queued job. */
    public void processReview(long prId, String headSha) {
        long t0 = System.currentTimeMillis();
        PullRequest pr = pullRequestRepository.findById(prId).orElse(null);
        if (pr == null) {
            log.warn("processReview: no PR with id={}", prId);
            return;
        }
        String modelUsed = aiReviewService.providerId() + ":" + aiReviewService.reviewModel();
        ReviewSession session;
        try {
            session = persistence.startOrReplaceSession(prId, headSha, modelUsed, prompts.reviewVersion());
        } catch (DataIntegrityViolationException race) {
            // UNIQUE(pr_id, head_sha) lost a race — another worker is already reviewing this SHA.
            log.info("Review for prId={} sha={} already in progress (race); bailing", prId, headSha);
            return;
        }
        long sessionId = session.getId();
        notifier.beginSession(prId, sessionId);

        String diff = diffSnapshots.getOrFetch(pr);
        FileContentProvider contents = headContents.forPullRequest(pr);
        List<DiffChunk> chunks = chunker.chunk(diff, llmProperties.maxTokensPerChunk(), contents);
        Set<String> changedPaths = changedPaths(chunks);
        ContextSource source = retrieval.sourceFor(pr.getRepoId());

        Run run = runChunks(chunks, source, changedPaths, new LiveStream(prId, sessionId));
        Filtered filtered = validateAndFilter(run.comments(), chunks, suppression.suppressedRuleKeys(pr.getRepoId()));
        int elapsed = (int) (System.currentTimeMillis() - t0);

        if (run.failed() == chunks.size() && !chunks.isEmpty()) {
            String reason = run.budgetExceeded() == chunks.size() ? "BUDGET_EXCEEDED" : "LLM_UNAVAILABLE";
            persistence.markFailed(prId, headSha, "all chunks failed (" + reason + ")", ReviewStatus.REVIEW_UNAVAILABLE, reason);
            notifier.sendStatus(prId, sessionId, ReviewStatus.REVIEW_UNAVAILABLE, reason);
            meterRegistry.counter("review.completed", "risk", "NONE", "status", "unavailable").increment();
            return;
        }

        List<DiffParser.FileDiff> files = diffParser.splitByFile(diff);
        RiskAssessment risk = riskScoring.score(new RiskScoringService.Input(files, filtered.kept(),
                retrieval.callerCounts(source, symbolsOf(chunks), changedPaths)));
        SummaryResult summary = summaries.summarize(pr.getTitle(),
                files.stream().map(DiffParser.FileDiff::path).toList(), filtered.kept(), risk);

        List<ReviewEvent.Usage> usages = new ArrayList<>(run.usages());
        if (summary.usage() != null) {
            usages.add(summary.usage());
        }
        long in = usages.stream().mapToLong(ReviewEvent.Usage::inputTokens).sum();
        long out = usages.stream().mapToLong(ReviewEvent.Usage::outputTokens).sum();
        BigDecimal cost = usages.stream()
                .map(u -> pricing.cost(u.model(), u.inputTokens(), u.outputTokens()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("servedBy", servedBy(run.usages()));
        metadata.put("fallbackChunks", run.fallbackChunks());
        metadata.put("failedChunks", run.failed());
        metadata.put("budgetExceededChunks", run.budgetExceeded());
        metadata.put("suppressed", filtered.suppressed());
        metadata.put("droppedAnchors", filtered.droppedAnchors());
        metadata.put("partial", run.failed() > 0);
        metadata.put("estimatedTokens", usages.stream().anyMatch(ReviewEvent.Usage::estimated));
        metadata.put("retrieval", Map.of(
                "enabled", !source.isEmpty(),
                "snippets", run.contexts().stream().mapToInt(RetrievedContext::snippetCount).sum(),
                "estimatedTokens", run.contexts().stream().mapToInt(RetrievedContext::estimatedTokens).sum(),
                "latencyMs", run.contexts().stream().mapToLong(RetrievedContext::latencyMs).sum()));

        persistence.completeReview(sessionId, prId, new ReviewOutcome(filtered.kept(), elapsed, in, out, cost,
                summary.text(), risk, metadata));
        gitHubService.postReviewSummary(prId,
                ReviewCommentFormatter.summaryMarkdown(risk, summary.text(), filtered.kept(), cost, modelUsed));

        meterRegistry.timer("review.duration").record(Duration.ofMillis(elapsed));
        meterRegistry.counter("review.completed", "risk", risk.level().name(), "status", "completed").increment();
        for (ReviewEvent.Usage u : usages) {
            meterRegistry.counter("llm.tokens.input", "provider", u.providerId(), "model", u.model()).increment(u.inputTokens());
            meterRegistry.counter("llm.tokens.output", "provider", u.providerId(), "model", u.model()).increment(u.outputTokens());
            meterRegistry.counter("llm.tokens.total").increment(u.total());
        }
        meterRegistry.counter("llm.cost.usd").increment(cost.doubleValue());
        langfuseTracer.traceReview(prId, headSha, modelUsed, prompts.reviewVersion(), (int) (in + out), elapsed,
                filtered.kept().size(), cost, servedBy(run.usages()));
        notifier.sendStatus(prId, sessionId, ReviewStatus.COMPLETED, null);
        log.info("Review complete prId={} comments={} risk={} elapsedMs={}", prId, filtered.kept().size(),
                risk.level(), elapsed);
    }

    /** Result of the internal eval endpoint: comments + accounting + risk, no streaming or persistence. */
    public record DiffReview(List<ReviewComment> comments, long inputTokens, long outputTokens, boolean estimated,
                             RiskAssessment risk, int retrievedSnippets) {
    }

    /** Used by the internal eval endpoint — reviews a raw diff (optionally with repo context files). */
    public DiffReview reviewDiff(String diff, Map<String, String> contextFiles, boolean useRetrieval) {
        Map<String, String> files = contextFiles == null ? Map.of() : contextFiles;
        List<DiffChunk> chunks = chunker.chunk(diff, llmProperties.maxTokensPerChunk(), FileContentProvider.of(files));
        Set<String> changedPaths = changedPaths(chunks);
        ContextSource source = useRetrieval ? retrieval.inMemory(files) : ContextSource.EMPTY;
        Run run = runChunks(chunks, source, changedPaths, new LiveStream(-1, -1));
        Filtered filtered = validateAndFilter(run.comments(), chunks, Set.of());
        RiskAssessment risk = riskScoring.score(new RiskScoringService.Input(diffParser.splitByFile(diff), filtered.kept(),
                retrieval.callerCounts(source, symbolsOf(chunks), changedPaths)));
        return new DiffReview(filtered.kept(),
                run.usages().stream().mapToLong(ReviewEvent.Usage::inputTokens).sum(),
                run.usages().stream().mapToLong(ReviewEvent.Usage::outputTokens).sum(),
                run.usages().stream().anyMatch(ReviewEvent.Usage::estimated),
                risk,
                run.contexts().stream().mapToInt(RetrievedContext::snippetCount).sum());
    }

    public void markFailed(long prId, String headSha, String error) {
        persistence.markFailed(prId, headSha, error, ReviewStatus.REVIEW_FAILED, "RETRIES_EXHAUSTED");
        pullRequestRepository.findById(prId).ifPresent(pr -> notifier.sendStatus(prId,
                pr.getLatestSessionId() == null ? 0 : pr.getLatestSessionId(), ReviewStatus.REVIEW_FAILED, "RETRIES_EXHAUSTED"));
    }

    // --- chunk fan-out -----------------------------------------------------------------------------

    private record Run(List<ReviewComment> comments, List<ReviewEvent.Usage> usages, List<RetrievedContext> contexts,
                       int failed, int budgetExceeded, int fallbackChunks) {
    }

    private record ChunkOutcome(List<ReviewComment> comments, List<ReviewEvent.Usage> usages, boolean failed,
                                boolean budgetExceeded, boolean fallback) {
    }

    private Run runChunks(List<DiffChunk> chunks, ContextSource source, Set<String> changedPaths, LiveStream live) {
        if (chunks.isEmpty()) {
            return new Run(List.of(), List.of(), List.of(), 0, 0, 0);
        }
        List<RetrievedContext> contexts = retrieval.retrieveAll(source, chunks, changedPaths);
        List<ChunkOutcome> outcomes = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChunkOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                int index = i;
                futures.add(executor.submit(() -> reviewOne(index, chunks.get(index), contexts.get(index), live)));
            }
            for (Future<ChunkOutcome> f : futures) {
                outcomes.add(join(f));
            }
        }
        Map<String, ReviewComment> byFingerprint = new LinkedHashMap<>();
        List<ReviewEvent.Usage> usages = new ArrayList<>();
        int failed = 0;
        int budget = 0;
        int fallback = 0;
        for (ChunkOutcome o : outcomes) {
            o.comments().forEach(c -> byFingerprint.putIfAbsent(c.fingerprint(), c));
            usages.addAll(o.usages());
            failed += o.failed() ? 1 : 0;
            budget += o.budgetExceeded() ? 1 : 0;
            fallback += o.fallback() ? 1 : 0;
        }
        return new Run(new ArrayList<>(byFingerprint.values()), usages, contexts, failed, budget, fallback);
    }

    private ChunkOutcome reviewOne(int index, DiffChunk chunk, RetrievedContext rc, LiveStream live) {
        ReviewPromptContext ctx = new ReviewPromptContext(chunk, rc.callers(), rc.tests(), prompts.reviewPrompt(), rc.related());
        ChunkCollector collector = new ChunkCollector(index, chunk, live);
        try {
            aiReviewService.stream(ctx).doOnNext(collector::on).blockLast(CHUNK_TIMEOUT);
            return new ChunkOutcome(collector.comments(), collector.usages(), false, false, collector.fallback);
        } catch (Exception e) {
            Throwable cause = Exceptions.unwrap(e);
            boolean budget = cause instanceof BudgetExceededException;
            log.warn("Chunk review failed for {}: {}", chunk.fileName(), cause.getMessage());
            live.error(index, chunk.fileName());
            return new ChunkOutcome(List.of(), collector.usages(), true, budget, collector.fallback);
        }
    }

    private ChunkOutcome join(Future<ChunkOutcome> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            return new ChunkOutcome(List.of(), List.of(), true, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChunkOutcome(List.of(), List.of(), true, false, false);
        }
    }

    /** Collects one chunk's events and streams each finding to the browser as it arrives. */
    private static final class ChunkCollector {
        private final int index;
        private final DiffChunk chunk;
        private final LiveStream live;
        private final Map<String, ReviewComment> findings = new LinkedHashMap<>();
        private final List<ReviewEvent.Usage> usages = new ArrayList<>();
        private boolean streamed;
        boolean fallback;

        ChunkCollector(int index, DiffChunk chunk, LiveStream live) {
            this.index = index;
            this.chunk = chunk;
            this.live = live;
        }

        synchronized void on(ReviewEvent event) {
            switch (event) {
                case ReviewEvent.Reset reset -> {
                    findings.clear();
                    if ("fallback".equals(reset.reason()) || "server-fallback".equals(reset.reason())) {
                        fallback = true;
                    }
                    if (streamed) {
                        live.reset(index);
                        streamed = false;
                    }
                }
                case ReviewEvent.Finding f -> {
                    String file = f.file() == null || f.file().isBlank() ? chunk.fileName() : f.file();
                    ReviewComment c = new ReviewComment(file, f.line(), f.severity(), f.text().trim(), f.codeSuggestion(),
                            f.confidence(), f.ruleKey(), null);
                    if (findings.putIfAbsent(c.fingerprint(), c) == null) {
                        live.finding(index, c);
                        streamed = true;
                    }
                }
                case ReviewEvent.Usage u -> usages.add(u);
            }
        }

        synchronized List<ReviewComment> comments() {
            return new ArrayList<>(findings.values());
        }

        synchronized List<ReviewEvent.Usage> usages() {
            return new ArrayList<>(usages);
        }
    }

    /** Where live events go: the PR's WebSocket topic, or nowhere (eval: prId &lt; 0). Seq is shared by all chunks. */
    private final class LiveStream {
        private final long prId;
        private final long sessionId;
        private final AtomicLong seq = new AtomicLong();

        LiveStream(long prId, long sessionId) {
            this.prId = prId;
            this.sessionId = sessionId;
        }

        private boolean disabled() {
            return prId < 0;
        }

        void finding(int chunkIndex, ReviewComment c) {
            if (disabled()) {
                return;
            }
            String[] words = c.commentText().split(" ");
            for (int i = 0; i < words.length; i++) {
                notifier.send(prId, new ReviewStreamToken(sessionId, seq.incrementAndGet(), chunkIndex, c.fingerprint(),
                        c.filePath(), c.lineNumber(), c.severity(), i == 0 ? words[i] : " " + words[i],
                        i == 0 ? c.confidence() : null, i == 0 ? c.ruleKey() : null));
            }
        }

        void reset(int chunkIndex) {
            if (disabled()) {
                return;
            }
            notifier.send(prId, new ReviewTopicMessage.Reset(sessionId, seq.incrementAndGet(), chunkIndex));
        }

        void error(int chunkIndex, String file) {
            if (disabled()) {
                return;
            }
            notifier.send(prId, new ReviewTopicMessage.ChunkError(sessionId, seq.incrementAndGet(), chunkIndex, file));
        }
    }

    // --- filtering ---------------------------------------------------------------------------------

    record Filtered(List<ReviewComment> kept, int suppressed, int droppedAnchors) {
    }

    private Filtered validateAndFilter(List<ReviewComment> comments, List<DiffChunk> chunks, Set<String> suppressedRules) {
        Set<String> anchors = computeValidAnchors(chunks);
        double threshold = llmProperties.confidenceThreshold();
        List<ReviewComment> kept = new ArrayList<>();
        int suppressed = 0;
        int dropped = 0;
        for (ReviewComment c : comments) {
            if (c.confidence() < threshold) {
                continue;
            }
            if (!anchors.isEmpty() && !anchors.contains(c.filePath() + "\t" + c.lineNumber())) {
                log.debug("Dropping hallucinated comment at {}:{}", c.filePath(), c.lineNumber());
                dropped++;
                continue;
            }
            if (suppressedRules.contains(c.ruleKey())) {
                suppressed++;
                continue;
            }
            kept.add(c);
        }
        return new Filtered(kept, suppressed, dropped);
    }

    /** Real (file, line) anchors that exist as added/context lines in the diff hunks. */
    private Set<String> computeValidAnchors(List<DiffChunk> chunks) {
        Set<String> anchors = new HashSet<>();
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
                if (raw.startsWith("-")) {
                    continue; // removed: no new-file line
                }
                anchors.add(chunk.fileName() + "\t" + line);
                line++;
            }
        }
        return anchors;
    }

    private static Set<String> changedPaths(List<DiffChunk> chunks) {
        Set<String> paths = new LinkedHashSet<>();
        chunks.forEach(c -> paths.add(c.fileName()));
        return paths;
    }

    private static Set<String> symbolsOf(List<DiffChunk> chunks) {
        Set<String> symbols = new LinkedHashSet<>();
        chunks.forEach(c -> symbols.addAll(c.symbols()));
        return symbols;
    }

    private static Map<String, Integer> servedBy(List<ReviewEvent.Usage> usages) {
        Map<String, Integer> out = new LinkedHashMap<>();
        usages.forEach(u -> out.merge(u.providerId() + ":" + u.model(), 1, Integer::sum));
        return out;
    }
}
