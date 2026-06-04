package com.codelensai.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bridges the Spring-managed {@code codelens.encryption.secret} into JPA {@link jakarta.persistence.AttributeConverter}s,
 * which JPA instantiates outside the Spring container (so they cannot be {@code @Autowired}). On
 * construction this captures the secret, derived to a 256-bit AES key via SHA-256, into a static field
 * the converter reads.
 */
@Component
public class EncryptionKeyProvider {

    private static volatile byte[] key;

    public EncryptionKeyProvider(@Value("${codelens.encryption.secret}") String secret) {
        key = deriveKey(secret);
    }

    /** 256-bit AES key, or {@code null} if no secret has been configured yet. */
    public static byte[] key() {
        return key;
    }

    private static byte[] deriveKey(String secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
