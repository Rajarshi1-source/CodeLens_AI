package com.codelensai.observability;

import com.codelensai.config.LangfuseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends a per-review trace to Langfuse (prompt version, model, tokens, latency) for LLM cost/quality
 * observability (master plan §18.1). Deliberately lightweight: one fire-and-forget {@code trace-create}
 * event per review via the public ingestion API. It is a no-op unless {@code codelens.langfuse.enabled=true}
 * and credentials are set, and it never throws into the review pipeline.
 */
@Component
public class LangfuseTracer {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;

    public LangfuseTracer(LangfuseProperties props) {
        this.restClient = props.isUsable() ? buildClient(props) : null;
        log.info("Langfuse tracing {}", props.isUsable() ? "enabled (host=" + props.host() + ")" : "disabled");
    }

    /** Best-effort: record one review as a Langfuse trace. Swallows all failures. */
    public void traceReview(long prId, String headSha, String model, String promptVersion,
                            int tokenCount, int elapsedMs, int commentCount) {
        if (restClient == null) {
            return;
        }
        try {
            String now = Instant.now().toString();
            Map<String, Object> event = Map.of(
                    "id", UUID.randomUUID().toString(),
                    "type", "trace-create",
                    "timestamp", now,
                    "body", Map.of(
                            "id", "pr-" + prId + "-" + headSha,
                            "name", "pr-review",
                            "timestamp", now,
                            "metadata", Map.of(
                                    "prId", prId,
                                    "headSha", headSha == null ? "" : headSha,
                                    "model", model == null ? "" : model,
                                    "promptVersion", promptVersion == null ? "" : promptVersion,
                                    "tokens", tokenCount,
                                    "latencyMs", elapsedMs,
                                    "comments", commentCount)));

            restClient.post()
                    .uri("/api/public/ingestion")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("batch", List.of(event)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("Langfuse trace failed for prId={}: {}", prId, e.getMessage());
        }
    }

    private static RestClient buildClient(LangfuseProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.host())
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(props.publicKey(), props.secretKey()))
                .build();
    }

    private static String basicAuth(String user, String pass) {
        String token = Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }
}
