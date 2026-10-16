package com.codelensai.service.llm;

import com.codelensai.config.ClaudeProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.enums.Severity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.codelensai.service.llm.FakeSseServer.sse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real Claude provider (official SDK) against a scripted Messages-API SSE stream. */
class AnthropicClaudeProviderTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ReviewPromptContext CTX = new ReviewPromptContext(
            new DiffChunk("src/A.java", 10, "@@ -10,1 +10,2 @@\n a();\n+b();\n", "java"),
            List.of("// src/Caller.java:1-2 (c)\nc();"), List.of(), "SYSTEM PROMPT", List.of());

    private static String delta(String text) {
        return sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":"
                + quote(text) + "}}");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String start(String model) {
        return sse("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"" + model + "\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":321,\"output_tokens\":1}}}")
                + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
    }

    private static String end(String stopReason, int outputTokens) {
        return sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + sse("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + outputTokens + "}}")
                + sse("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static final String FIRST = "{\"comments\":[{\"file\":\"src/A.java\",\"line\":11,\"severity\":\"CRITICAL\","
            + "\"category\":\"correctness\",\"comment\":\"b() can throw\",\"suggestion\":\"safeB();\",\"confidence\":0.8}";
    private static final String SECOND = ",{\"file\":\"src/A.java\",\"line\":10,\"severity\":\"SUGGESTION\","
            + "\"category\":\"style\",\"comment\":\"rename a\",\"suggestion\":null,\"confidence\":0.5}]}";

    private static AnthropicClaudeProvider provider(String baseUrl, String model) {
        return new AnthropicClaudeProvider(new ClaudeProperties("test-key", baseUrl, model, model, "high", 16000,
                4000, true, 30), MAPPER);
    }

    @Test
    void streamsFindingsIncrementallyAndReportsUsage() throws Exception {
        try (FakeSseServer server = new FakeSseServer(start("claude-opus-5-5") + delta(FIRST), delta(SECOND) + end("end_turn", 77))) {
            List<ReviewEvent> events = provider(server.baseUrl(), "claude-opus-5-5").streamReview(CTX)
                    .doOnNext(e -> {
                        if (e instanceof ReviewEvent.Finding) {
                            server.release();
                        }
                    })
                    .collectList().block(Duration.ofSeconds(30));
            assertThat(server.releasedByConsumer()).as("first finding arrived before the stream finished").isTrue();
            assertThat(events).hasSize(3);
            ReviewEvent.Finding f = (ReviewEvent.Finding) events.get(0);
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.ruleKey()).isEqualTo("llm.correctness");
            assertThat(f.codeSuggestion()).isEqualTo("safeB();");
            ReviewEvent.Usage usage = (ReviewEvent.Usage) events.get(2);
            assertThat(usage.inputTokens()).isEqualTo(321);
            assertThat(usage.outputTokens()).isEqualTo(77);
            assertThat(usage.estimated()).isFalse();

            JsonNode body = MAPPER.readTree(server.requestBodies.get(0));
            assertThat(body.has("temperature")).as("Opus 5.5 rejects sampling params").isFalse();
            assertThat(body.has("thinking")).as("thinking is always on; never configure it").isFalse();
            assertThat(body.path("output_config").path("effort").asString()).isEqualTo("high");
            assertThat(body.path("output_config").path("format").path("schema").path("required").toString()).contains("comments");
            assertThat(body.path("fallbacks").asString()).isEqualTo("default");
            assertThat(body.path("system").toString()).contains("SYSTEM PROMPT");
            assertThat(body.path("messages").get(0).toString()).contains("BEGIN RELATED CALLERS").doesNotContain("SYSTEM PROMPT");
            assertThat(header(server, "anthropic-beta")).contains(AnthropicClaudeProvider.SERVER_FALLBACK_BETA);
        }
    }

    @Test
    void haikuGetsNoServerSideFallback() throws Exception {
        try (FakeSseServer server = new FakeSseServer(start("claude-haiku-5-5") + delta("{\"comments\":[]}"), end("end_turn", 3))) {
            server.release();
            provider(server.baseUrl(), "claude-haiku-5-5").streamReview(CTX).collectList().block(Duration.ofSeconds(30));
            assertThat(MAPPER.readTree(server.requestBodies.get(0)).has("fallbacks")).isFalse();
            assertThat(String.valueOf(header(server, "anthropic-beta"))).doesNotContain("server-side-fallback");
        }
    }

    @Test
    void refusalSurfacesAsLlmRefusalException() throws Exception {
        try (FakeSseServer server = new FakeSseServer(start("claude-opus-5-5"), end("refusal", 0))) {
            server.release();
            assertThatThrownBy(() -> provider(server.baseUrl(), "claude-opus-5-5").streamReview(CTX)
                    .collectList().block(Duration.ofSeconds(30)))
                    .isInstanceOf(LlmRefusalException.class);
        }
    }

    @Test
    void secondAnswerBlockResetsThePartialFirstAnswer() throws Exception {
        String restart = sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":"
                + quote("{\"comments\":[{\"line\":10,\"severity\":\"WARNING\",\"comment\":\"from the fallback model\"}]}") + "}}")
                + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                + sse("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":9}}")
                + sse("message_stop", "{\"type\":\"message_stop\"}");
        try (FakeSseServer server = new FakeSseServer(start("claude-opus-5-5") + delta("{\"comments\":[{\"line\":10,\"comm"), restart)) {
            server.release();
            List<ReviewEvent> events = provider(server.baseUrl(), "claude-opus-5-5").streamReview(CTX)
                    .collectList().block(Duration.ofSeconds(30));
            assertThat(events.get(0)).isEqualTo(new ReviewEvent.Reset("server-fallback"));
            assertThat(events).filteredOn(e -> e instanceof ReviewEvent.Finding).hasSize(1)
                    .first().satisfies(e -> assertThat(((ReviewEvent.Finding) e).text()).isEqualTo("from the fallback model"));
        }
    }

    @Test
    void missingKeyFailsFast() {
        AnthropicClaudeProvider p = new AnthropicClaudeProvider(new ClaudeProperties(null, null, null, null, null,
                null, null, null, null), MAPPER);
        assertThatThrownBy(() -> p.streamReview(CTX).blockLast()).hasMessageContaining("ANTHROPIC_API_KEY");
        assertThat(AnthropicClaudeProvider.supportsServerFallback("claude-opus-5-5")).isTrue();
        assertThat(AnthropicClaudeProvider.supportsServerFallback("claude-haiku-5-5")).isFalse();
    }

    private static String header(FakeSseServer server, String name) {
        for (Map.Entry<String, List<String>> e : server.requestHeaders.get(0).entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return String.join(",", e.getValue());
            }
        }
        return null;
    }
}
