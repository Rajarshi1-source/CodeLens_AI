package com.codelensai.support;

import com.codelensai.TestcontainersConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A full Spring context against Testcontainers Postgres (pgvector) + Redis. Skipped locally without Docker,
 * fails in CI ({@code CODELENS_REQUIRE_DOCKER=true}) — see {@link RequireDockerExtension}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequireDockerExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "codelens.internal.enabled=true",
        "codelens.indexing.enabled=true"
})
public @interface CodelensIntegrationTest {
}
