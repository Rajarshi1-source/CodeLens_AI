package com.codelensai.config;

import com.codelensai.service.llm.AnthropicClaudeProvider;
import com.codelensai.service.llm.LlmReviewProvider;
import com.codelensai.service.llm.LocalModelProvider;
import com.codelensai.service.llm.OpenAiGpt5Provider;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Selects the active {@link LlmReviewProvider} from {@code codelens.llm.provider}. The concrete
 * providers are Spring beans (so the OpenAI one gets its {@link WebClient}); the {@code @Primary}
 * selector bean is what services inject. The MVP default is {@code local} (offline, no API key);
 * {@code gpt5} uses the real OpenAI provider; {@code claude} remains a stub.
 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, LangfuseProperties.class})
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
    public LocalModelProvider localModelProvider() {
        return new LocalModelProvider();
    }

    @Bean
    public OpenAiGpt5Provider openAiGpt5Provider(WebClient openAiWebClient,
                                                 ObjectMapper objectMapper,
                                                 LlmProperties props) {
        return new OpenAiGpt5Provider(openAiWebClient, objectMapper, props);
    }

    @Bean
    public AnthropicClaudeProvider anthropicClaudeProvider() {
        return new AnthropicClaudeProvider();
    }

    @Bean
    @Primary
    public LlmReviewProvider llmReviewProvider(LlmProperties props,
                                               LocalModelProvider local,
                                               OpenAiGpt5Provider gpt5,
                                               AnthropicClaudeProvider claude) {
        LlmReviewProvider provider = switch (props.provider()) {
            case "local" -> local;
            case "gpt5" -> gpt5;
            case "claude" -> claude;
            default -> throw new IllegalStateException("Unknown LLM provider: " + props.provider());
        };
        log.info("Active LLM provider: {} (review-model={})", provider.providerId(), props.reviewModel());
        return provider;
    }
}
