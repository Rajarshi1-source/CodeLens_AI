package com.codelensai.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} header against the raw request body.
 * Uses constant-time comparison ({@link MessageDigest#isEqual}) so a timing attacker
 * cannot learn how many leading bytes of the HMAC matched.
 */
@Component
public class WebhookSignatureValidator {

    private static final String ALGO = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final String webhookSecret;

    public WebhookSignatureValidator(@Value("${github.webhook-secret}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    /** Verify against the global {@code github.webhook-secret}. */
    public boolean isValid(String rawBody, String signatureHeader) {
        return isValid(rawBody, signatureHeader, webhookSecret);
    }

    /** Verify against a specific secret (per-repository hooks created by "connect repository"). */
    public boolean isValid(String rawBody, String signatureHeader, String secret) {
        if (rawBody == null || signatureHeader == null || secret == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        String expectedHex = signatureHeader.substring(PREFIX.length());
        String computedHex = hmacSha256Hex(rawBody, secret);
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                computedHex.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(String body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }
}
