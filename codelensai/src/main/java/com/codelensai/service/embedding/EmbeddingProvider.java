package com.codelensai.service.embedding;

import java.util.List;

/**
 * Config-driven embedding adapter for G1 repo-aware retrieval, mirroring the sealed LLM adapter. The
 * active provider is chosen by {@code codelens.embedding.provider}. Vectors from different providers are
 * not comparable, so every stored vector is tagged with {@link #providerId()} and retrieval only compares
 * like with like (switching providers requires a re-index).
 */
public sealed interface EmbeddingProvider permits HashingEmbeddingProvider, OpenAiEmbeddingProvider {

    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);

    int dimensions();

    String providerId();

    /** pgvector literal: {@code [0.1,0.2,...]}. */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
