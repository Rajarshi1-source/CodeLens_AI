package com.codelensai.service.llm;

import com.codelensai.config.LlmProperties;
import com.codelensai.model.dto.DiffChunk;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.SummaryContext;
import com.codelensai.model.dto.SummaryResult;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real OpenAI (GPT-5-class) provider behind the sealed {@link LlmReviewProvider} adapter.
 *
 * <p>Uses chat completions in <b>streaming</b> mode (SSE) with a strict {@code json_schema} response
 * format. Text deltas are fed to a {@link StreamingCommentParser} as they arrive, so each comment is
 * emitted the moment it completes — the browser sees the first finding while the model is still
 * writing. {@code stream_options.include_usage} adds a final chunk carrying real token usage.
 *
 * <p>Reasoning models reject sampling parameters, so no {@code temperature} is sent and the output cap
 * is {@code max_completion_tokens}. The diff and retrieved context are delimited untrusted data
 * (see {@link ReviewPromptRenderer}). Selected when {@code codelens.llm.provider=gpt5}.
 */
public final class OpenAiGpt5Provider implements LlmReviewProvider {

    private static final String DONE = "[DONE]";

    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;
    private final LlmProperties props;

    public OpenAiGpt5Provider(WebClient openAiWebClient, ObjectMapper objectMapper, LlmProperties props) {
        this.openAiWebClient = openAiWebClient;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public Flux<ReviewEvent> streamReview(ReviewPromptContext ctx) {
        if (!hasKey()) {
            return Flux.error(new IllegalStateException(
                    "OPENAI_API_KEY not set — set it or use codelens.llm.provider=local"));
        }
        return Flux.defer(() -> {
            DiffChunk chunk = ctx.chunk();
            StreamingCommentParser parser = new StreamingCommentParser(objectMapper, chunk);
            AtomicReference<ReviewEvent.Usage> usage = new AtomicReference<>();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.reviewModel());
            body.put("max_completion_tokens", props.maxOutputTokens());
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
            body.put("response_format", Map.of("type", "json_schema",
                    "json_schema", Map.of("name", "code_review", "strict", true, "schema", ReviewOutputSchema.schema())));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", ctx.systemContext()),
                    Map.of("role", "user", "content", ReviewPromptRenderer.userPrompt(ctx, false))));
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
                    .concatMapIterable(data -> onChunk(data, parser, usage))
                    .concatWith(Mono.fromSupplier(() -> usage.get() != null ? usage.get()
                            : new ReviewEvent.Usage(providerId(), props.reviewModel(),
                            ReviewPromptRenderer.estimateTokens(ReviewPromptRenderer.userPrompt(ctx, true)), 0, true)));
        });
    }

    private List<ReviewEvent> onChunk(String data, StreamingCommentParser parser, AtomicReference<ReviewEvent.Usage> usage) {
        List<ReviewEvent> out = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (Exception e) {
            return out;
        }
        JsonNode content = root.path("choices").path(0).path("delta").path("content");
        if (content.isString()) {
            out.addAll(parser.feed(content.asString()));
        }
        JsonNode u = root.path("usage");
        if (u.isObject()) {
            usage.set(new ReviewEvent.Usage(providerId(), root.path("model").asString(props.reviewModel()),
                    u.path("prompt_tokens").asLong(0), u.path("completion_tokens").asLong(0), false));
        }
        return out;
    }

    @Override
    public Mono<SummaryResult> summarize(SummaryContext ctx) {
        if (!hasKey()) {
            return Mono.error(new IllegalStateException("OPENAI_API_KEY not set"));
        }
        String model = props.summaryModel() == null || props.summaryModel().isBlank()
                ? props.reviewModel() : props.summaryModel();
        Map<String, Object> body = Map.of(
                "model", model,
                "max_completion_tokens", 2000,
                "messages", List.of(
                        Map.of("role", "system", "content", ctx.systemContext()),
                        Map.of("role", "user", "content", SummaryPrompt.render(ctx))));
        return openAiWebClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + props.openaiApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(root -> new SummaryResult(
                        root.path("choices").path(0).path("message").path("content").asString("").trim(),
                        new ReviewEvent.Usage(providerId(), root.path("model").asString(model),
                                root.path("usage").path("prompt_tokens").asLong(0),
                                root.path("usage").path("completion_tokens").asLong(0), false)));
    }

    private boolean hasKey() {
        return props.openaiApiKey() != null && !props.openaiApiKey().isBlank();
    }

    @Override
    public String providerId() {
        return "gpt5";
    }

    @Override
    public String reviewModel() {
        return props.reviewModel();
    }
}
