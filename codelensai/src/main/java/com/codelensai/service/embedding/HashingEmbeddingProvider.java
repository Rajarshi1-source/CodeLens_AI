package com.codelensai.service.embedding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, deterministic code embedder (the default). Identifiers are split on camelCase / snake_case and
 * lower-cased; unigrams plus adjacent-token bigrams are signed-feature-hashed (FNV-1a 64-bit, sign from a
 * hash bit) into {@code dimensions} buckets, weighted by {@code log(1 + tf)} and L2-normalised.
 *
 * <p>It is not a semantic model, but code that shares vocabulary (the same types, methods and fields)
 * lands close together, which is what "find the related code" mostly needs — and it makes CI, the eval
 * gate and the demo reproducible with no API key.
 */
public final class HashingEmbeddingProvider implements EmbeddingProvider {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_+");
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final int dimensions;

    public HashingEmbeddingProvider(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        Map<String, Integer> tf = new HashMap<>();
        List<String> tokens = tokens(text);
        for (int i = 0; i < tokens.size(); i++) {
            tf.merge(tokens.get(i), 1, Integer::sum);
            if (i > 0) {
                tf.merge(tokens.get(i - 1) + " " + tokens.get(i), 1, Integer::sum);
            }
        }
        float[] v = new float[dimensions];
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            long h = fnv1a(e.getKey());
            int bucket = (int) Long.remainderUnsigned(h, dimensions);
            float sign = ((h >>> 63) & 1L) == 0 ? 1f : -1f;
            v[bucket] += sign * (float) Math.log1p(e.getValue());
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        texts.forEach(t -> out.add(embed(t)));
        return out;
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = IDENT.matcher(text);
        while (m.find()) {
            for (String part : CAMEL.split(m.group())) {
                if (part.length() >= 2) {
                    out.add(part.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static long fnv1a(String s) {
        long h = FNV_OFFSET;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        return h;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String providerId() {
        return "hashing-v1";
    }
}
