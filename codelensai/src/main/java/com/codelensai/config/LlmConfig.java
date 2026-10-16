package com.codelensai.config;

import com.codelensai.service.llm.AnthropicClaudeProvider;
import com.codelensai.service.llm.LlmProviderRegistry;
import com.codelensai.service.llm.LlmReviewProvider;
import com.codelensai.service.llm.LocalModelProvider;
import com.codelensai.service.llm.OpenAiGpt5Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wires the LLM providers. The concrete providers are beans (so the OpenAI one gets its {@link WebClient}
 * and Claude its SDK settings); {@link LlmProviderRegistry} picks the primary from
 * {@code codelens.llm.provider} and the failover target from {@code codelens.llm.fallback-provider}.
 * The default is {@code local} (offline, no API key); {@code gpt5} and {@code claude} are real providers.
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** Dedicated WebClient for OpenAI (large buffer for streamed completions). */
    @Bean
    public WebClient openAiWebClient(LlmProperties props) {
        return WebClient.builder()
                .baseUrl(props.openaiBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Bean
    public LocalModelProvider localModelProvider(@Value("${codelens.llm.local.pace-ms:0}") long paceMs) {
        return new LocalModelProvider(Duration.ofMillis(paceMs));
    }

    @Bean
    public OpenAiGpt5Provider openAiGpt5Provider(WebClient openAiWebClient,
                                                 ObjectMapper objectMapper,
                                                 LlmProperties props) {
        return new OpenAiGpt5Provider(openAiWebClient, objectMapper, props);
    }

    @Bean
    public AnthropicClaudeProvider anthropicClaudeProvider(ClaudeProperties claudeProperties, ObjectMapper objectMapper) {
        return new AnthropicClaudeProvider(claudeProperties, objectMapper);
    }

    @Bean
    public LlmProviderRegistry llmProviderRegistry(LlmProperties props,
                                                   LocalModelProvider local,
                                                   OpenAiGpt5Provider gpt5,
                                                   AnthropicClaudeProvider claude) {
        Map<String, LlmReviewProvider> byId = List.<LlmReviewProvider>of(local, gpt5, claude).stream()
                .collect(Collectors.toMap(LlmProviderRegistry::idOf, p -> p));
        LlmProviderRegistry registry = new LlmProviderRegistry(byId, props.provider(), props.fallbackProvider());
        log.info("Active LLM provider: {} (review-model={}), fallback: {}",
                registry.primary().providerId(), registry.primary().reviewModel(),
                registry.fallbackFor(registry.primary()).map(LlmReviewProvider::providerId).orElse("none"));
        return registry;
    }

    @Bean
    @Primary
    public LlmReviewProvider llmReviewProvider(LlmProviderRegistry registry) {
        return registry.primary();
    }
}
