package com.codelensai.config;

import com.codelensai.service.embedding.EmbeddingClient;
import com.codelensai.service.embedding.EmbeddingProvider;
import com.codelensai.service.embedding.HashingEmbeddingProvider;
import com.codelensai.service.embedding.OpenAiEmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link EmbeddingProvider} from {@code codelens.embedding.provider}. Fails fast when the
 * configured dimensions differ from the {@code vector(512)} column the schema fixes.
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties props, EmbeddingClient client) {
        if (props.dimensions() != EmbeddingProperties.SCHEMA_DIMENSIONS) {
            throw new IllegalStateException("codelens.embedding.dimensions=" + props.dimensions()
                    + " but code_chunks.embedding is vector(" + EmbeddingProperties.SCHEMA_DIMENSIONS + ")");
        }
        EmbeddingProvider provider = switch (props.provider()) {
            case "hashing" -> new HashingEmbeddingProvider(props.dimensions());
            case "openai" -> new OpenAiEmbeddingProvider(client, props.openaiModel(), props.dimensions(), props.batchSize());
            default -> throw new IllegalStateException("Unknown embedding provider: " + props.provider());
        };
        log.info("Embedding provider: {}", provider.providerId());
        return provider;
    }
}
