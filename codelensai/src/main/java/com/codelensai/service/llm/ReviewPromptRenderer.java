package com.codelensai.service.llm;

import com.codelensai.model.dto.ReviewPromptContext;
import com.codelensai.util.PromptBuilder;

/**
 * The one place a review prompt is rendered, so every provider (local, OpenAI, Claude) sees the same
 * delimited diff + retrieved context. Chat providers send the system prompt as a separate system message
 * ({@code includeSystem=false}); the local provider renders it inline to estimate input tokens.
 */
public final class ReviewPromptRenderer {

    public static final String OUTPUT_FORMAT =
            "a JSON object {\"comments\": [{\"file\": string, \"line\": number, "
                    + "\"severity\": \"CRITICAL\"|\"WARNING\"|\"SUGGESTION\", \"category\": string, "
                    + "\"comment\": string, \"suggestion\": string|null, \"confidence\": number 0..1}]}. "
                    + "Return an empty comments array if the diff is clean.";

    private ReviewPromptRenderer() {
    }

    public static String userPrompt(ReviewPromptContext ctx, boolean includeSystem) {
        return new PromptBuilder()
                .withSystemContext(includeSystem ? ctx.systemContext() : "")
                .withDiffChunk(ctx.chunk())
                .withLanguageHint(ctx.chunk() == null ? "" : ctx.chunk().language())
                .withRetrievedContext(ctx.callers(), ctx.tests())
                .withRelated(ctx.related())
                .withOutputFormat(OUTPUT_FORMAT)
                .build();
    }

    /** Same heuristic as the chunker: ~4 chars/token plus scaffold headroom. */
    public static long estimateTokens(String text) {
        return text == null ? 0 : (text.length() / 4) + 64;
    }
}
