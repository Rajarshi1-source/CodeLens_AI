package com.codelensai.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Infrastructure gate for the Testcontainers-backed tests.
 *
 * <ul>
 *   <li>Docker available: the tests use Testcontainers (pgvector Postgres + Redis).</li>
 *   <li>No Docker but {@code CODELENS_IT_EXTERNAL_INFRA=true}: the tests run against the Postgres/Redis
 *       named by the environment (e.g. the WSL setup from {@code scripts/wsl-infra-setup.sh}).</li>
 *   <li>Otherwise, locally, the test class is <b>aborted</b> (reported as skipped) so the rest of the suite
 *       still runs — but with {@code CODELENS_REQUIRE_DOCKER=true} (set in CI) a missing Docker daemon
 *       <b>fails</b> the build, so the integration tests can never be silently skipped where they must run.</li>
 * </ul>
 * This replaces {@code @Testcontainers(disabledWithoutDocker = true)}, whose execution condition runs before
 * any extension and therefore cannot be turned into a failure.
 */
public class RequireDockerExtension implements BeforeAllCallback {

    static final String REQUIRE_ENV = "CODELENS_REQUIRE_DOCKER";
    static final String EXTERNAL_ENV = "CODELENS_IT_EXTERNAL_INFRA";

    private static volatile Boolean dockerAvailable;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (dockerAvailable()) {
            return;
        }
        if (Boolean.parseBoolean(System.getenv(REQUIRE_ENV))) {
            throw new IllegalStateException(REQUIRE_ENV + "=true but no Docker daemon is reachable: "
                    + context.getDisplayName() + " cannot run its Testcontainers dependencies");
        }
        if (Boolean.parseBoolean(System.getenv(EXTERNAL_ENV))) {
            return;
        }
        Assumptions.abort("Docker is not available; skipping " + context.getDisplayName()
                + " (set " + EXTERNAL_ENV + "=true to use external Postgres/Redis, or "
                + REQUIRE_ENV + "=true to make this a failure)");
    }

    public static boolean dockerAvailable() {
        Boolean cached = dockerAvailable;
        if (cached == null) {
            try {
                cached = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable t) {
                cached = false;
            }
            dockerAvailable = cached;
        }
        return cached;
    }
}
