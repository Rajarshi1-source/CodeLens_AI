package com.codelensai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Loads versioned prompt files from the classpath ({@code /prompts/review_<version>.txt}).
 * Prompts are product code: versioning them makes every stored review say which prompt produced it
 * ({@code review_sessions.prompt_version}) and lets an eval gate block a regressing prompt change.
 */
@Component
public class PromptRepository {

    private final String reviewVersion;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptRepository(@Value("${codelens.prompts.review-version:v1}") String reviewVersion) {
        this.reviewVersion = reviewVersion;
    }

    public String reviewPrompt() {
        return cache.computeIfAbsent("review_" + reviewVersion, this::loadFromClasspath);
    }

    public String reviewVersion() {
        return reviewVersion;
    }

    private String loadFromClasspath(String name) {
        try (InputStream in = getClass().getResourceAsStream("/prompts/" + name + ".txt")) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource /prompts/" + name + ".txt");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load prompt " + name, e);
        }
    }
}
