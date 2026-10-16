package com.codelensai.service.retrieval;

import com.codelensai.repository.CodeIndexRepository.Snippet;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Where G1 context comes from: the repository's pgvector index ({@link PgVectorContextSource}) or, for the
 * eval endpoint and fixtures, files supplied in memory ({@link InMemoryContextSource}). Both answer the
 * same three questions — who calls these symbols, which tests cover this file, what code looks similar.
 */
public interface ContextSource {

    ContextSource EMPTY = new ContextSource() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public List<Snippet> nearest(float[] v, int k, Collection<String> exclude) {
            return List.of();
        }

        @Override
        public List<Snippet> callers(Collection<String> symbols, Collection<String> exclude, int limit) {
            return List.of();
        }

        @Override
        public List<Snippet> tests(Collection<String> baseNames, Collection<String> exclude, int limit) {
            return List.of();
        }

        @Override
        public Map<String, Integer> callerCounts(Collection<String> symbols, Collection<String> exclude) {
            return Map.of();
        }
    };

    boolean isEmpty();

    List<Snippet> nearest(float[] v, int k, Collection<String> exclude);

    List<Snippet> callers(Collection<String> symbols, Collection<String> exclude, int limit);

    List<Snippet> tests(Collection<String> baseNames, Collection<String> exclude, int limit);

    Map<String, Integer> callerCounts(Collection<String> symbols, Collection<String> exclude);
}
