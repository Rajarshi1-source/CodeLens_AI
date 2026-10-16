package com.codelensai.service.retrieval;

import com.codelensai.repository.CodeIndexRepository;
import com.codelensai.repository.CodeIndexRepository.Snippet;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Context from a repository's pgvector index, restricted to vectors from the active embedding provider. */
public final class PgVectorContextSource implements ContextSource {

    private final CodeIndexRepository index;
    private final long repoId;
    private final String embeddingProvider;

    public PgVectorContextSource(CodeIndexRepository index, long repoId, String embeddingProvider) {
        this.index = index;
        this.repoId = repoId;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public List<Snippet> nearest(float[] v, int k, Collection<String> exclude) {
        return index.nearest(repoId, embeddingProvider, v, k, exclude);
    }

    @Override
    public List<Snippet> callers(Collection<String> symbols, Collection<String> exclude, int limit) {
        return index.callers(repoId, symbols, exclude, limit);
    }

    @Override
    public List<Snippet> tests(Collection<String> baseNames, Collection<String> exclude, int limit) {
        return index.tests(repoId, baseNames, exclude, limit);
    }

    @Override
    public Map<String, Integer> callerCounts(Collection<String> symbols, Collection<String> exclude) {
        return index.callerCounts(repoId, symbols, exclude);
    }
}
