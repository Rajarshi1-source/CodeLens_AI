package com.codelensai.service.embedding;

import com.codelensai.config.EmbeddingProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HTTP client for the OpenAI embeddings endpoint, wrapped in the {@code embedding} breaker + retry. */
@Component
public class EmbeddingClient {

    private final WebClient webClient;
    private final EmbeddingProperties props;

    public EmbeddingClient(EmbeddingProperties props) {
        this.props = props;
        this.webClient = WebClient.builder()
                .baseUrl(props.openaiBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @CircuitBreaker(name = "embedding")
    @Retry(name = "embedding")
    public List<float[]> embed(String model, int dimensions, List<String> inputs) {
        if (props.openaiApiKey() == null || props.openaiApiKey().isBlank()) {
            throw new IllegalStateException("codelens.embedding.openai-api-key is not set");
        }
        JsonNode root = webClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + props.openaiApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", model, "dimensions", dimensions, "input", inputs))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(60));
        List<float[]> out = new ArrayList<>(inputs.size());
        if (root != null) {
            for (JsonNode item : root.path("data")) {
                JsonNode emb = item.path("embedding");
                float[] v = new float[emb.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = (float) emb.get(i).asDouble();
                }
                out.add(v);
            }
        }
        if (out.size() != inputs.size()) {
            throw new IllegalStateException("embeddings response size " + out.size() + " != inputs " + inputs.size());
        }
        return out;
    }
}
