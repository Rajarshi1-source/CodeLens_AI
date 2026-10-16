package com.codelensai.service.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawContentBlockStartEvent;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.codelensai.config.ClaudeProperties;
import com.codelensai.model.dto.ReviewEvent;
import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.model.dto.SummaryContext;
import com.codelensai.model.dto.SummaryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Real Anthropic Claude provider, built on the official {@code anthropic-java} SDK.
 *
 * <p>Streaming: the SDK's blocking {@code createStreaming} runs on a virtual thread and is bridged into a
 * {@link Flux} with {@code Flux.create}; text deltas feed a {@link StreamingCommentParser}, so each
 * comment is emitted as soon as it completes. Thinking deltas are ignored. Usage comes from the
 * accumulated final message.
 *
 * <p>Request rules for Claude Opus 5.5 (the default): thinking is always on, so the request sends
 * <b>no</b> {@code thinking} config and <b>no</b> {@code temperature}; depth is set with
 * {@code output_config.effort} (explicitly — the model's default is {@code medium}); {@code max_tokens}
 * leaves room for thinking plus the JSON answer; the output is constrained with
 * {@code output_config.format} (JSON schema). The server-side refusal fallback
 * ({@code fallbacks:"default"}) is enabled except on Haiku, which does not support it; when it takes over
 * mid-stream the provider emits {@link ReviewEvent.Reset} so the partial answer is discarded. A refusal
 * that is not rescued surfaces as {@link LlmRefusalException}.
 *
 * <p>SDK retries are disabled ({@code maxRetries(0)}): Resilience4j owns retry/circuit-breaking.
 */
public final class AnthropicClaudeProvider implements LlmReviewProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClaudeProvider.class);
    static final String SERVER_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final ClaudeProperties props;
    private final ObjectMapper objectMapper;
    private volatile AnthropicClient client;

    public AnthropicClaudeProvider(ClaudeProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<ReviewEvent> streamReview(ReviewPromptContext ctx) {
        if (!props.hasApiKey()) {
            return Flux.error(new IllegalStateException(
                    "ANTHROPIC_API_KEY not set — set it or use codelens.llm.provider=local"));
        }
        return Flux.create(sink -> {
            Thread worker = Thread.ofVirtual().name("claude-stream").start(() -> run(ctx, sink));
            sink.onDispose(worker::interrupt);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    MessageCreateParams reviewParams(ReviewPromptContext ctx) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(props.reviewModel())
                .maxTokens(props.maxTokens())
                .system(ctx.systemContext())
                .addUserMessage(ReviewPromptRenderer.userPrompt(ctx, false))
                .outputConfig(BetaOutputConfig.builder()
                        .effort(BetaOutputConfig.Effort.of(props.effort().toLowerCase()))
                        .format(jsonSchemaFormat())
                        .build());
        applyServerFallback(b, props.reviewModel());
        return b.build();
    }

    private void run(ReviewPromptContext ctx, FluxSink<ReviewEvent> sink) {
        StreamingCommentParser parser = new StreamingCommentParser(objectMapper, ctx.chunk());
        BetaMessageAccumulator acc = BetaMessageAccumulator.create();
        int textBlocks = 0;
        try (StreamResponse<BetaRawMessageStreamEvent> response = client().beta().messages().createStreaming(reviewParams(ctx))) {
            sink.onDispose(response::close);
            for (BetaRawMessageStreamEvent event : (Iterable<BetaRawMessageStreamEvent>) response.stream()::iterator) {
                if (sink.isCancelled()) {
                    return;
                }
                acc.accumulate(event);
                if (event.isContentBlockStart()) {
                    BetaRawContentBlockStartEvent.ContentBlock block = event.asContentBlockStart().contentBlock();
                    boolean fallbackBlock = !block.isText() && block._json().map(Object::toString).orElse("").contains("fallback");
                    if (block.isText()) {
                        textBlocks++;
                    }
                    // A second answer block (or an explicit fallback block) means the server-side refusal
                    // fallback restarted the answer on another model: discard the partial first answer.
                    if (fallbackBlock || (block.isText() && textBlocks > 1)) {
                        parser.reset();
                        sink.next(new ReviewEvent.Reset("server-fallback"));
                    }
                } else if (event.isContentBlockDelta()) {
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isText()) {
                        parser.feed(delta.asText().text()).forEach(sink::next);
                    }
                }
            }
            BetaMessage message = acc.message();
            if (message.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false)) {
                String category = message.stopDetails().flatMap(d -> d.category()).map(c -> c.asString()).orElse(null);
                String explanation = message.stopDetails().flatMap(d -> d.explanation()).orElse(null);
                sink.error(new LlmRefusalException(category, explanation));
                return;
            }
            if (message.stopReason().map(BetaStopReason.MAX_TOKENS::equals).orElse(false)) {
                log.warn("Claude hit max_tokens={} on {} — keeping the {} finding(s) already parsed",
                        props.maxTokens(), ctx.chunk() == null ? "?" : ctx.chunk().fileName(), parser.emitted());
            }
            sink.next(new ReviewEvent.Usage(providerId(), message.model().asString(),
                    message.usage().inputTokens(), message.usage().outputTokens(), false));
            sink.complete();
        } catch (Exception e) {
            if (!sink.isCancelled()) {
                sink.error(e);
            }
        }
    }

    @Override
    public Mono<SummaryResult> summarize(SummaryContext ctx) {
        if (!props.hasApiKey()) {
            return Mono.error(new IllegalStateException("ANTHROPIC_API_KEY not set"));
        }
        return Mono.fromCallable(() -> {
            MessageCreateParams.Builder b = MessageCreateParams.builder()
                    .model(props.summaryModel())
                    .maxTokens(props.summaryMaxTokens())
                    .system(SummaryPrompt.SYSTEM)
                    .addUserMessage(SummaryPrompt.render(ctx))
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build());
            applyServerFallback(b, props.summaryModel());
            BetaMessage message = client().beta().messages().create(b.build());
            if (message.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false)) {
                throw new LlmRefusalException(null, "summary refused");
            }
            String text = message.content().stream()
                    .filter(BetaContentBlock::isText)
                    .map(blk -> blk.asText().text())
                    .collect(Collectors.joining("\n"))
                    .trim();
            return new SummaryResult(text, new ReviewEvent.Usage(providerId(), message.model().asString(),
                    message.usage().inputTokens(), message.usage().outputTokens(), false));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Server-side refusal fallback: supported on Opus/Sonnet/Fable, never on Haiku. */
    private void applyServerFallback(MessageCreateParams.Builder b, String model) {
        if (Boolean.TRUE.equals(props.serverFallback()) && supportsServerFallback(model)) {
            b.addBeta(SERVER_FALLBACK_BETA);
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
    }

    static boolean supportsServerFallback(String model) {
        return model != null && !model.toLowerCase().contains("haiku");
    }

    private BetaJsonOutputFormat jsonSchemaFormat() {
        Map<String, JsonValue> schema = new LinkedHashMap<>();
        ReviewOutputSchema.schema().forEach((k, v) -> schema.put(k, JsonValue.from(v)));
        return BetaJsonOutputFormat.builder()
                .schema(BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema).build())
                .build();
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder()
                            .apiKey(props.apiKey())
                            .maxRetries(0)
                            .timeout(Duration.ofSeconds(props.timeoutSeconds()));
                    if (props.baseUrl() != null && !props.baseUrl().isBlank()) {
                        b.baseUrl(props.baseUrl());
                    }
                    c = b.build();
                    client = c;
                }
            }
        }
        return c;
    }

    @Override
    public String providerId() {
        return "claude";
    }

    @Override
    public String reviewModel() {
        return props.reviewModel();
    }
}
