package com.codelensai.service.retrieval;

import com.codelensai.repository.CodeIndexRepository.IndexChunk;
import com.codelensai.repository.CodeIndexRepository.Snippet;
import com.codelensai.service.embedding.EmbeddingProvider;
import com.codelensai.service.index.CodeChunker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The same retrieval semantics as {@link PgVectorContextSource}, over files held in memory and searched by
 * brute-force cosine. Used by the eval endpoint ({@code context_files}) so cross-file cases are measurable
 * without a database index, and in tests.
 */
public final class InMemoryContextSource implements ContextSource {

    private static final Pattern TEST_PATH =
            Pattern.compile("(?i)(^|/)(test|tests|spec|__tests__)/|(test|spec|tests)\\.[a-z]+$|_test\\.");

    private record Entry(Snippet snippet, IndexChunk chunk, float[] vector) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public InMemoryContextSource(Map<String, String> files, CodeChunker chunker, EmbeddingProvider embedding) {
        long id = 1;
        List<IndexChunk> all = new ArrayList<>();
        files.forEach((path, content) -> all.addAll(chunker.chunk(path, content)));
        List<float[]> vectors = embedding.embedBatch(all.stream().map(c -> c.path() + "\n" + c.content()).toList());
        for (int i = 0; i < all.size(); i++) {
            IndexChunk c = all.get(i);
            entries.add(new Entry(new Snippet(id++, c.path(), c.symbol(), c.startLine(), c.endLine(), c.content()),
                    c, vectors.get(i)));
        }
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public List<Snippet> nearest(float[] v, int k, Collection<String> exclude) {
        return entries.stream()
                .filter(e -> !exclude.contains(e.snippet().path()))
                .sorted(Comparator.comparingDouble((Entry e) -> -EmbeddingProvider.cosine(v, e.vector())))
                .limit(k)
                .map(Entry::snippet)
                .toList();
    }

    @Override
    public List<Snippet> callers(Collection<String> symbols, Collection<String> exclude, int limit) {
        return entries.stream()
                .filter(e -> !exclude.contains(e.snippet().path()))
                .filter(e -> e.chunk().refs().stream().anyMatch(symbols::contains))
                .limit(limit)
                .map(Entry::snippet)
                .toList();
    }

    @Override
    public List<Snippet> tests(Collection<String> baseNames, Collection<String> exclude, int limit) {
        return entries.stream()
                .filter(e -> !exclude.contains(e.snippet().path()))
                .filter(e -> TEST_PATH.matcher(e.snippet().path()).find())
                .filter(e -> baseNames.stream().anyMatch(b -> e.snippet().path().toLowerCase().contains(b.toLowerCase())))
                .limit(limit)
                .map(Entry::snippet)
                .toList();
    }

    @Override
    public Map<String, Integer> callerCounts(Collection<String> symbols, Collection<String> exclude) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String s : symbols) {
            int n = (int) entries.stream()
                    .filter(e -> !exclude.contains(e.snippet().path()))
                    .filter(e -> e.chunk().refs().contains(s))
                    .count();
            if (n > 0) {
                out.put(s, n);
            }
        }
        return out;
    }
}
