package com.codelensai.model.dto;

import com.codelensai.model.enums.Severity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A completed, schema-validated comment produced by the review pipeline. Invariants enforced in the
 * compact constructor.
 *
 * <p>{@code fingerprint} identifies "the same finding" across re-reviews of a PR: it hashes the anchor
 * and the rule, not the prose (LLM wording drifts between runs; the anchor and rule don't). Feedback is
 * re-linked by it, and the live stream groups tokens by it.
 */
public record ReviewComment(
        String filePath,
        int lineNumber,
        Severity severity,
        String commentText,
        String codeSuggestion,
        double confidence,
        String ruleKey,
        String fingerprint
) {
    public ReviewComment {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath required");
        }
        if (lineNumber < 0) {
            throw new IllegalArgumentException("lineNumber must be >= 0");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity required");
        }
        if (commentText == null || commentText.isBlank()) {
            throw new IllegalArgumentException("commentText required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        if (ruleKey == null || ruleKey.isBlank()) {
            ruleKey = "llm.general";
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = fingerprintOf(filePath, lineNumber, ruleKey);
        }
    }

    public static ReviewComment of(ReviewEvent.Finding f) {
        return new ReviewComment(f.file(), f.line(), f.severity(), f.text().trim(), f.codeSuggestion(),
                f.confidence(), f.ruleKey(), null);
    }

    /** First 32 hex chars of SHA-256 over {@code file|line|ruleKey}. */
    public static String fingerprintOf(String file, int line, String ruleKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((file + "|" + line + "|" + ruleKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
