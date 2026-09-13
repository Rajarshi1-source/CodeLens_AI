package com.codelensai.service.llm;

import com.codelensai.config.LlmProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.ReviewToken;
import com.codelensai.model.enums.Severity;
import com.codelensai.util.PromptBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real OpenAI (GPT-5-class) provider behind the sealed {@link LlmReviewProvider} adapter.
 *
 * <p>Uses the chat-completions endpoint in <b>streaming</b> mode (SSE): the model's token deltas
 * arrive as {@code data:} events, which we accumulate and then parse as structured JSON
 * ({@code {"comments":[{file,line,severity,comment,confidence}]}}) — never regex-parsed from prose.
 * Each parsed comment is fragmented into word-level {@link ReviewToken}s so the downstream pipeline
 * streams it to the browser word-by-word, exactly like the mock provider.
 *
 * <p>The diff is passed as clearly-delimited untrusted data via {@link PromptBuilder} to blunt
 * prompt-injection. Selected only when {@code codelens.llm.provider=gpt5}; the MVP default is local.
 */
public final class OpenAiGpt5Provider implements LlmReviewProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGpt5Provider.class);
    private static final String DONE = "[DONE]";
    private static final String OUTPUT_FORMAT =
            "a JSON object {\"comments\": [{\"file\": string, \"line\": number, "
                    + "\"severity\": \"CRITICAL\"|\"WARNING\"|\"SUGGESTION\", "
                    + "\"comment\": string, \"confidence\": number 0..1}]}. "
                    + "Return an empty comments array if the diff is clean.";

    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;
    private final LlmProperties props;

    public OpenAiGpt5Provider(WebClient openAiWebClient, ObjectMapper objectMapper, LlmProperties props) {
        this.openAiWebClient = openAiWebClient;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public Flux<ReviewToken> streamReview(ReviewPromptContext ctx) {
        if (props.openaiApiKey() == null || props.openaiApiKey().isBlank()) {
            return Flux.error(new IllegalStateException(
                    "OPENAI_API_KEY not set — set it or use codelens.llm.provider=local"));
        }
        DiffChunk chunk = ctx.chunk();
        String userPrompt = new PromptBuilder()
                .withSystemContext(ctx.systemContext())
                .withDiffChunk(chunk)
                .withLanguageHint(chunk.language())
                .withOutputFormat(OUTPUT_FORMAT)
                .build();

        Map<String, Object> body = Map.of(
                "model", props.reviewModel(),
                "temperature", props.temperature(),
                "max_tokens", props.maxOutputTokens(),
                "stream", true,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", ctx.systemContext()),
                        Map.of("role", "user", "content", userPrompt)));

        return openAiWebClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + props.openaiApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil(DONE::equals)
                .filter(data -> !DONE.equals(data))
                .map(this::extractDelta)
                .reduce(new StringBuilder(), StringBuilder::append)
                .flatMapMany(sb -> Flux.fromIterable(parseComments(sb.toString(), chunk)));
    }

    /** Pull {@code choices[0].delta.content} out of one streamed chunk; "" if absent. */
    private String extractDelta(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode content = root.path("choices").path(0).path("delta").path("content");
            return content.isString() ? content.asString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private List<ReviewToken> parseComments(String json, DiffChunk chunk) {
        List<ReviewToken> tokens = new ArrayList<>();
        if (json.isBlank()) {
            return tokens;
        }
        try {
            JsonNode comments = objectMapper.readTree(json).path("comments");
            for (JsonNode c : comments) {
                String file = c.path("file").asString(chunk.fileName());
                int line = c.path("line").asInt(chunk.startLine());
                Severity severity = parseSeverity(c.path("severity").asString("SUGGESTION"));
                String text = c.path("comment").asString("");
                if (text.isBlank()) {
                    continue;
                }
                streamWords(tokens, file, line, severity, text);
            }
        } catch (Exception e) {
            log.warn("Failed to parse OpenAI structured output for {}: {}", chunk.fileName(), e.getMessage());
        }
        return tokens;
    }

    private void streamWords(List<ReviewToken> tokens, String file, int line, Severity severity, String text) {
        String[] words = text.split(" ");
        for (int i = 0; i < words.length; i++) {
            String fragment = i == 0 ? words[i] : " " + words[i];
            tokens.add(new ReviewToken(file, line, severity, fragment));
        }
    }

    private Severity parseSeverity(String raw) {
        try {
            return Severity.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return Severity.SUGGESTION;
        }
    }

    @Override
    public String providerId() {
        return "gpt5";
    }
}
