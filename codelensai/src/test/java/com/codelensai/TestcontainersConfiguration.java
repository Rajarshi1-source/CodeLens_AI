package com.codelensai;

import com.codelensai.support.RequireDockerExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres with the pgvector extension (G1 code index) + Redis, wired via {@code @ServiceConnection}.
 * The pgvector image is a drop-in substitute for the official postgres image.
 *
 * <p>Without Docker the containers are not created; with {@code CODELENS_IT_EXTERNAL_INFRA=true} the tests
 * then run against the datasource/Redis from the environment ({@code SPRING_DATASOURCE_URL},
 * {@code SPRING_REDIS_HOST}) — e.g. the WSL Postgres+pgvector set up by {@code scripts/wsl-infra-setup.sh}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final DockerImageName PGVECTOR = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    @Conditional(DockerAvailable.class)
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(PGVECTOR);
    }

    @Bean
    @ServiceConnection(name = "redis")
    @Conditional(DockerAvailable.class)
    @SuppressWarnings("resource") // container lifecycle is managed by Spring/Testcontainers, not closed here
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    static class DockerAvailable implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return RequireDockerExtension.dockerAvailable();
        }
    }
}
