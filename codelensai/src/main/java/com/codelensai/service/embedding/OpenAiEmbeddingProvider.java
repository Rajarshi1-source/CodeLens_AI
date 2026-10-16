package com.codelensai.service.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI {@code text-embedding-3-small} reduced to the schema's 512 dimensions ({@code dimensions=512}),
 * batched. The HTTP call lives in {@link EmbeddingClient} (a separate bean) so its Resilience4j
 * annotations are actually woven.
 */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingClient client;
    private final String model;
    private final int dimensions;
    private final int batchSize;

    public OpenAiEmbeddingProvider(EmbeddingClient client, String model, int dimensions, int batchSize) {
        this.client = client;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> slice = texts.subList(i, Math.min(texts.size(), i + batchSize))
                    .stream().map(t -> t == null || t.isBlank() ? " " : t).toList();
            out.addAll(client.embed(model, dimensions, slice));
        }
        return out;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String providerId() {
        return "openai:" + model + ":" + dimensions;
    }
}
