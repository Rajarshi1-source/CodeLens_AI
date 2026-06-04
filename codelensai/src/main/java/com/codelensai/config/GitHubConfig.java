package com.codelensai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@link WebClient} for the GitHub REST API. The bearer token is per-user (resolved at call time from
 * the repo owner's stored credential), so it is NOT set here — only the base URL, API version, and a
 * larger in-memory buffer for big unified diffs.
 */
@Configuration
public class GitHubConfig {

    /** Diffs can be large; lift the default 256 KB reactive buffer. */
    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    @Bean
    public WebClient githubWebClient(@Value("${github.api-base-url:https://api.github.com}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "CodeLens-AI")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }
}
