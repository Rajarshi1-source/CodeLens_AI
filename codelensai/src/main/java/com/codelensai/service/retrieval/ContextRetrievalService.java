package com.codelensai.service.retrieval;

import com.codelensai.config.RetrievalProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.repository.CodeIndexRepository;
import com.codelensai.repository.CodeIndexRepository.Snippet;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.service.embedding.EmbeddingProvider;
import com.codelensai.service.index.CodeChunker;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G1 repo-aware context retrieval. For each diff chunk it gathers, in priority order:
 * <ol>
 *   <li><b>callers</b> of the symbols the chunk changes (symbol graph — "you changed this signature but not
 *       its two callers");</li>
 *   <li><b>tests</b> for the changed file;</li>
 *   <li><b>related</b> code by vector similarity (pgvector cosine).</li>
 * </ol>
 * Files changed in the PR are excluded (their indexed base version would mislead the model). Whole
 * snippets are added until the per-chunk token budget ({@code codelens.retrieval.max-context-tokens}) is
 * spent, so retrieval can never blow the prompt.
 */
@Service
public class ContextRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(ContextRetrievalService.class);
    private static final Set<String> TRIVIAL = Set.of("get", "set", "of", "main", "run", "apply", "build",
            "toString", "equals", "hashCode", "init", "test", "value", "values");

    private final RetrievalProperties props;
    private final CodeIndexRepository index;
    private final RepositoryRepository repositories;
    private final EmbeddingProvider embedding;
    private final CodeChunker chunker;
    private final MeterRegistry meters;

    public ContextRetrievalService(RetrievalProperties props, CodeIndexRepository index, RepositoryRepository repositories,
                                   EmbeddingProvider embedding, CodeChunker chunker, MeterRegistry meters) {
        this.props = props;
        this.index = index;
        this.repositories = repositories;
        this.embedding = embedding;
        this.chunker = chunker;
        this.meters = meters;
    }

    /** The repo's index when it is READY, otherwise no context (retrieval degrades, the review still runs). */
    public ContextSource sourceFor(Long repoId) {
        if (!Boolean.TRUE.equals(props.enabled()) || repoId == null) {
            return ContextSource.EMPTY;
        }
        return repositories.findById(repoId)
                .filter(r -> "READY".equals(r.getIndexStatus()))
                .<ContextSource>map(r -> new PgVectorContextSource(index, r.getId(), embedding.providerId()))
                .orElse(ContextSource.EMPTY);
    }

    public ContextSource inMemory(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return ContextSource.EMPTY;
        }
        return new InMemoryContextSource(files, chunker, embedding);
    }

    public List<RetrievedContext> retrieveAll(ContextSource source, List<DiffChunk> chunks, Collection<String> changedPaths) {
        List<RetrievedContext> out = new ArrayList<>();
        if (source.isEmpty() || chunks.isEmpty() || !Boolean.TRUE.equals(props.enabled())) {
            chunks.forEach(c -> out.add(RetrievedContext.EMPTY));
            return out;
        }
        List<float[]> vectors;
        try {
            vectors = embedding.embedBatch(chunks.stream().map(c -> c.fileName() + "\n" + c.content()).toList());
        } catch (RuntimeException e) {
            log.warn("Embedding the diff failed; retrieving by symbols only: {}", e.getMessage());
            vectors = null;
        }
        Set<String> exclude = new HashSet<>(changedPaths);
        for (int i = 0; i < chunks.size(); i++) {
            out.add(retrieve(source, chunks.get(i), vectors == null ? null : vectors.get(i), exclude));
        }
        return out;
    }

    RetrievedContext retrieve(ContextSource source, DiffChunk chunk, float[] vector, Set<String> exclude) {
        long t0 = System.nanoTime();
        int budget = props.maxContextTokens();
        Set<Long> seen = new HashSet<>();
        List<String> callers = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        List<String> related = new ArrayList<>();
        int[] used = {0};
        try {
            Set<String> symbols = interestingSymbols(chunk.symbols());
            for (Snippet s : source.callers(symbols, exclude, props.maxCallers())) {
                add(s, callers, seen, used, budget);
            }
            for (Snippet s : source.tests(baseNames(chunk.fileName()), exclude, props.maxTests())) {
                add(s, tests, seen, used, budget);
            }
            if (vector != null) {
                for (Snippet s : source.nearest(vector, props.topK() * 2, exclude)) {
                    if (related.size() >= props.topK()) {
                        break;
                    }
                    add(s, related, seen, used, budget);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Retrieval failed for {} — reviewing without context: {}", chunk.fileName(), e.getMessage());
        }
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        meters.timer("retrieval.latency").record(Duration.ofMillis(ms));
        DistributionSummary.builder("retrieval.snippets").register(meters).record(callers.size() + tests.size() + related.size());
        return new RetrievedContext(callers, tests, related, used[0], ms);
    }

    /** Fan-in of the changed symbols (used by the risk score). */
    public Map<String, Integer> callerCounts(ContextSource source, Collection<String> symbols, Collection<String> changedPaths) {
        if (source.isEmpty()) {
            return Map.of();
        }
        try {
            return source.callerCounts(interestingSymbols(symbols), new HashSet<>(changedPaths));
        } catch (RuntimeException e) {
            log.warn("Caller counts unavailable: {}", e.getMessage());
            return Map.of();
        }
    }

    private void add(Snippet s, List<String> target, Set<Long> seen, int[] used, int budget) {
        if (!seen.add(s.id())) {
            return;
        }
        String formatted = format(s);
        int cost = formatted.length() / 4 + 8;
        if (used[0] + cost > budget) {
            return;
        }
        used[0] += cost;
        target.add(formatted);
    }

    String format(Snippet s) {
        String[] lines = s.content().split("\n", -1);
        int max = props.maxSnippetLines();
        String body = lines.length <= max ? s.content()
                : String.join("\n", Arrays.copyOfRange(lines, 0, max)) + "\n// ...";
        int end = Math.min(s.endLine(), s.startLine() + Math.min(lines.length, max) - 1);
        return "// " + s.path() + ":" + s.startLine() + "-" + end + (s.symbol() == null ? "" : " (" + s.symbol() + ")")
                + "\n" + body;
    }

    private static Set<String> interestingSymbols(Collection<String> symbols) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : symbols) {
            if (s != null && s.length() > 2 && !TRIVIAL.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    static Set<String> baseNames(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = name.indexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.length() > 2 ? Set.of(base) : Set.of();
    }
}
