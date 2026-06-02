package com.codelensai.config;

import com.codelensai.service.llm.AnthropicClaudeProvider;
import com.codelensai.service.llm.LlmReviewProvider;
import com.codelensai.service.llm.LocalModelProvider;
import com.codelensai.service.llm.OpenAiGpt5Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link LlmReviewProvider} from {@code codelens.llm.provider}. The MVP ships
 * {@code local}; the {@code gpt5}/{@code claude} providers are stubs until their WebClient SSE calls
 * are implemented. Services depend on the sealed interface and never know which concrete model runs.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmReviewProvider llmReviewProvider(LlmProperties props) {
        LlmReviewProvider provider = switch (props.provider()) {
            case "local" -> new LocalModelProvider();
            case "gpt5" -> new OpenAiGpt5Provider();
            case "claude" -> new AnthropicClaudeProvider();
            default -> throw new IllegalStateException("Unknown LLM provider: " + props.provider());
        };
        log.info("Active LLM provider: {} (review-model={})", provider.providerId(), props.reviewModel());
        return provider;
    }
}
