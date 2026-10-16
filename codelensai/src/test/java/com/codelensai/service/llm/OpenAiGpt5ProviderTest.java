package com.codelensai.service.llm;

import com.codelensai.config.LlmProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static com.codelensai.service.llm.FakeSseServer.sse;
import static org.assertj.core.api.Assertions.assertThat;

/** The OpenAI provider against a scripted chat-completions SSE stream. */
class OpenAiGpt5ProviderTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static String chunk(String content) {
        return sse(null, "{\"choices\":[{\"delta\":{\"content\":" + quote(content) + "}}]}");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void streamsIncrementallyWithStrictSchemaAndRealUsage() throws Exception {
        String first = chunk("{\"comments\":[{\"file\":\"src/A.java\",\"line\":11,\"severity\":\"WARNING\",\"category\":\"concurrency\",")
                + chunk("\"comment\":\"race\",\"suggestion\":null,\"confidence\":0.7}");
        String second = chunk("]}")
                + sse(null, "{\"model\":\"gpt-5\",\"choices\":[],\"usage\":{\"prompt_tokens\":210,\"completion_tokens\":40}}")
                + sse(null, "[DONE]");
        try (FakeSseServer server = new FakeSseServer(first, second)) {
            LlmProperties props = new LlmProperties("gpt5", "gpt-5", "gpt-5-mini", null, 8000, null, null, null,
                    "sk-test", server.baseUrl());
            OpenAiGpt5Provider provider = new OpenAiGpt5Provider(WebClient.builder().baseUrl(server.baseUrl()).build(), MAPPER, props);
            ReviewPromptContext ctx = new ReviewPromptContext(new DiffChunk("src/A.java", 10, "@@ -10 +10,2 @@\n+x++;\n", "java"),
                    List.of(), List.of("// test"), "SYSTEM PROMPT", List.of());

            List<ReviewEvent> events = provider.streamReview(ctx)
                    .doOnNext(e -> {
                        if (e instanceof ReviewEvent.Finding) {
                            server.release();
                        }
                    })
                    .collectList().block(Duration.ofSeconds(30));

            assertThat(server.releasedByConsumer()).as("finding emitted before the response ended").isTrue();
            assertThat(events).hasSize(2);
            assertThat(((ReviewEvent.Finding) events.get(0)).ruleKey()).isEqualTo("llm.concurrency");
            ReviewEvent.Usage usage = (ReviewEvent.Usage) events.get(1);
            assertThat(usage.inputTokens()).isEqualTo(210);
            assertThat(usage.outputTokens()).isEqualTo(40);

            JsonNode body = MAPPER.readTree(server.requestBodies.get(0));
            assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_schema");
            assertThat(body.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
            assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(8000);
            String user = body.path("messages").get(1).path("content").asString();
            assertThat(user).doesNotContain("SYSTEM PROMPT").contains("BEGIN RELATED TESTS");
        }
    }
}
