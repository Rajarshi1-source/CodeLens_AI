# CodeLens AI — Build & Run Reference (Maven, Spring Boot 4.1.x, Java 21)

The complete project-initialization and build path for the CodeLens backend. Load when generating the project, editing `pom.xml`, or setting up the local/Docker build. Locked choices: **Maven** (build tool), **PostgreSQL** (not MySQL), **Spring Boot 4.1.x** on **Java 21**.

## Contents
1. Locked decisions (and why)
2. Spring Initializr selections
3. Generate with one curl command
4. Full `pom.xml`
5. Build & run on Windows 11
6. Maven Docker build stage
7. Notes
8. Boot 3 -> Boot 4 gotchas
9. Local infrastructure without Docker (WSL Ubuntu)
10. Demo profile, tests, E2E and eval
11. Environment variables

## 1. Locked decisions (and why)

- **Maven, not Gradle** — the dev environment already has Maven 3.9.x + JDK 21 verified; Initializr defaults to Maven; for a single-module service the Gradle speed edge is marginal. The generated `mvnw` wrapper pins the build's Maven version regardless of the local install.
- **PostgreSQL, not MySQL** — CodeLens depends on `JSONB`, `tsvector` + GIN full-text search, an ACID multi-statement review-completion transaction, and `pgvector` (G1). MySQL does these poorly or not at all. Use the **PostgreSQL Driver**.
- **Spring Boot 4.1.x** — the 3.5 line reached open-source end-of-life at **3.5.16 on 2026-06-30**, so staying on 3.x means unpatched CVEs. 4.1.x is current GA on **Spring Framework 7.0 / Spring Security 7.1**, and Initializr no longer serves any 3.x version at all. (An earlier revision of this document argued Boot 4 was "not worth the migration"; 3.5 going EOL settled that.)
- **Java 21, not 25** — Boot 4.1.x is tested on Java 17–26, so 21 LTS remains fully supported. No reason to move.
- **Boot 4.1.x, not 4.0.x** — 4.0.x ships Hibernate 7.2, whose JSON `FormatMapper` auto-detection only knows Jackson 2. Since Boot 4 defaults to **Jackson 3**, `@JdbcTypeCode(SqlTypes.JSON)` columns fail at runtime on 4.0.x with *"Could not find a FormatMapper for the JSON format"*. Hibernate 7.4.5 (Boot 4.1.x) adds `Jackson3JsonFormatMapper` and fixes it. CodeLens has JSON columns on both `review_sessions` and `review_comments`, so 4.1.x is a hard floor, not a preference.

## 2. Spring Initializr selections

Project **Maven**, Language **Java**, Spring Boot **4.1.x** (the current default), Java **21**, Packaging **Jar**. Group `com.codelensai`, Artifact `codelensai`.

Dependencies: Spring Web · WebSocket · Spring Data JPA · Spring Security · OAuth2 Client · OAuth2 Resource Server · Spring Data Redis · Validation · Spring Boot Actuator · PostgreSQL Driver · Lombok · Testcontainers · Prometheus (Observability). Optional: Spring Boot DevTools, Docker Compose Support.

Three things are **not** available on Initializr and must be added to `pom.xml` by hand (§4):

| Manual add | Why it has no Initializr id |
|---|---|
| `spring-boot-starter-webclient` | Initializr only offers `webflux` (the whole reactive *server* stack). CodeLens wants `WebClient` + `Flux` only, so take the leaner starter manually. |
| `spring-boot-starter-aspectj` | No Initializr entry. Required for the Resilience4j annotations — see §8. |
| `resilience4j-spring-boot4` | Resilience4j has never been on Initializr. |
| `com.anthropic:anthropic-java` | The official Anthropic Java SDK for the Claude provider (brings Jackson 2 + OkHttp; coexists with Boot's Jackson 3 — different packages). |
| `com.github.javaparser:javaparser-core` | G4 structure-aware chunking: exact Java method/class ranges, pure Java. |

pgvector needs **no Java dependency**: the `vector` extension is created by `schema.sql` (`CREATE EXTENSION IF NOT EXISTS vector`) and `code_chunks` / `code_symbols` are accessed through `JdbcClient` with `CAST(:v AS vector)`. The database must be a pgvector build (`pgvector/pgvector:pg16` image, or the `postgresql-16-pgvector` package).

## 3. Generate with one curl command

Windows 11 ships `curl.exe`. Use `curl.exe` explicitly (PowerShell aliases bare `curl` to `Invoke-WebRequest`):

```
curl.exe https://start.spring.io/starter.zip -o codelensai.zip ^
 -d type=maven-project -d language=java -d bootVersion=4.1.1 -d javaVersion=21 ^
 -d groupId=com.codelensai -d artifactId=codelensai -d name=codelensai -d packageName=com.codelensai ^
 -d dependencies=web,websocket,data-jpa,data-redis,security,oauth2-client,oauth2-resource-server,validation,actuator,postgresql,lombok,testcontainers,prometheus
```

If curl returns HTTP 400 it is almost always `bootVersion` — Initializr only accepts versions it is actively serving, and it drops old lines quickly (as of this writing it serves only 4.1.1 and 4.0.8 as GA). Check the website dropdown for the exact current patch. Then unzip into e.g. `C:\dev\codelensai`.

Note that `web` still resolves to `spring-boot-starter-web` on Initializr even though `spring-boot-starter-webmvc` is the canonical Boot 4 name for the same contents; §4 uses the canonical name.

## 4. Full `pom.xml`

This is the project's actual `pom.xml` — Initializr selections plus the manual adds from §2. Testcontainers, Micrometer and Jackson versions are managed by the Spring Boot parent BOM; Resilience4j, the Anthropic SDK and JavaParser are pinned. Surefire, Failsafe and `spring-boot:run` force `-Duser.timezone=UTC` (PostgreSQL 16+ rejects legacy JVM zone ids such as `Asia/Calcutta`, and the token budget is keyed by UTC day).

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <groupId>com.codelensai</groupId>
    <artifactId>codelensai</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>codelensai</name>
    <description>CodeLens AI — real-time collaborative code review</description>
    <properties>
        <java.version>21</java.version>
        <resilience4j.version>2.4.0</resilience4j.version>
        <anthropic-java.version>2.34.0</anthropic-java.version>
        <javaparser.version>3.26.4</javaparser.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webclient</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
        <!-- Boot 4 removed spring-boot-starter-aop; resilience4j-spring-boot4 does NOT
             pull an AOP starter transitively (spring-boot-starter-aop did). Without this,
             @CircuitBreaker/@Retry/@Bulkhead are silently not woven. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aspectj</artifactId>
        </dependency>

        <!-- Resilience4j (NOT managed by the Boot BOM — pin explicitly) -->
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot4</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-reactor</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>

        <!-- Official Anthropic SDK for the Claude provider. It brings Jackson 2 (com.fasterxml) and OkHttp;
             these coexist with Boot 4's Jackson 3 (tools.jackson) because the packages differ. -->
        <dependency>
            <groupId>com.anthropic</groupId>
            <artifactId>anthropic-java</artifactId>
            <version>${anthropic-java.version}</version>
        </dependency>
        <!-- G4 structure-aware chunking: exact Java method/class ranges (pure Java, no native libs). -->
        <dependency>
            <groupId>com.github.javaparser</groupId>
            <artifactId>javaparser-core</artifactId>
            <version>${javaparser.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-docker-compose</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!--
              Runs the *IT integration tests. Surefire only matches *Test/Test*/*Tests, so
              ReviewSessionIdempotencyIT was silently never executed. Version, the
              integration-test+verify goal bindings, and classesDirectory all come from the
              Spring Boot parent's pluginManagement. Bound to `verify`, NOT `package`.
            -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <configuration>
                    <argLine>-Duser.timezone=UTC</argLine>
                </configuration>
            </plugin>
            <!-- UTC JVM for tests: PostgreSQL 18 rejects legacy tz aliases (e.g. Asia/Calcutta) that some
                 OS defaults still report, and all persisted timestamps are Instants anyway. -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <argLine>-Duser.timezone=UTC</argLine>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <jvmArguments>-Duser.timezone=UTC</jvmArguments>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <executions>
                    <execution>
                        <id>default-compile</id>
                        <phase>compile</phase>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                        <configuration>
                            <annotationProcessorPaths>
                                <path>
                                    <groupId>org.projectlombok</groupId>
                                    <artifactId>lombok</artifactId>
                                </path>
                            </annotationProcessorPaths>
                        </configuration>
                    </execution>
                    <execution>
                        <id>default-testCompile</id>
                        <phase>test-compile</phase>
                        <goals>
                            <goal>testCompile</goal>
                        </goals>
                        <configuration>
                            <annotationProcessorPaths>
                                <path>
                                    <groupId>org.projectlombok</groupId>
                                    <artifactId>lombok</artifactId>
                                </path>
                            </annotationProcessorPaths>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

</project>
```

Enable virtual threads in `application.yml`: `spring.threads.virtual.enabled: true` (see `codelens-java21`).

G1 retrieval uses pgvector through plain SQL (see §2) — no extra Java dependency.

## 5. Build & run on Windows 11

From the project root (use the generated wrapper; on Windows it's `mvnw.cmd`):

```
cd C:\dev\codelensai
mvnw.cmd clean verify           REM unit tests (Surefire) + *IT integration tests (Failsafe) + jar
mvnw.cmd clean package          REM unit tests ONLY -- stops before integration-test
mvnw.cmd spring-boot:run        REM run locally
mvnw.cmd clean package -DskipTests   REM skip tests for a fast packaging run
```

**Use `verify`, not `package`, as the full test command** -- including in CI. Surefire only matches `*Test` / `Test*` / `*Tests`, so integration tests are named `*IT` and run under `maven-failsafe-plugin`, which binds to the `integration-test` and `verify` phases. Both of those run *after* `package`, so `mvnw package` builds the jar while silently skipping every `*IT` class. This project learned that the hard way: `ReviewSessionIdempotencyIT` existed for the entire MVP and never once executed, because there was no failsafe plugin declared and CI ran `package`.

The wrapper downloads and uses its own pinned Maven, so the locally installed Maven version is irrelevant to the build. Boot 4 needs Maven 3.9+, which the wrapper satisfies. (On macOS/Linux the same commands are `./mvnw ...`.)

## 6. Maven Docker build stage

Multi-stage Dockerfile build stage:

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# The wrapper loses its executable bit on checkouts created from Windows; restore it before use.
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Java 21 base images stay correct on Boot 4.1.x.

## 7. Notes

- `spring-boot-starter-webmvc` gives the servlet stack on Tomcat (REST + WebSocket); `spring-boot-starter-webclient` supplies `WebClient` and `Flux` for the LLM streaming adapter without the reactive *server*. There is no MVC-vs-reactive ambiguity to reason about: no controller returns `Flux`/`Mono`, and reactive types stay internal to the provider adapter and the STOMP relay.
- LLM providers sit behind the sealed `LlmReviewProvider`: OpenAI through `WebClient` (SSE), Claude through the official Anthropic Java SDK (`maxRetries(0)` — Resilience4j owns retries), and the offline `local` provider.
- Keep secrets (GitHub app id/secret, LLM API key, DB creds) in environment variables, never in `application.yml` committed to the repo.

## 8. Boot 3 -> Boot 4 gotchas
9. Local infrastructure without Docker (WSL Ubuntu)
10. Demo profile, tests, E2E and eval
11. Environment variables

Things that bite on this project specifically. All verified against Boot 4.1.1.

- **`spring-boot-starter-aop` no longer exists.** It is `spring-boot-starter-aspectj`. This is the dangerous one: `resilience4j-spring-boot4` does *not* pull an AOP starter transitively the way `resilience4j-spring-boot3` did, so omitting it turns every `@CircuitBreaker` / `@Retry` / `@Bulkhead` into a **silent no-op**. The app starts, all tests pass, and the resilience layer is simply absent. `ResilienceAspectsTest` exists to catch exactly this.
- **Jackson 3 is the default.** `com.fasterxml.jackson.databind.*` becomes `tools.jackson.databind.*`. Annotations do *not* move (`jackson-annotations` stays at `com.fasterxml.jackson.core`). `JsonNode.isTextual()`/`asText()` are deprecated in favour of `isString()`/`asString()`; `asString()` keeps Jackson 2's coercing behaviour, whereas `stringValue()` throws on a non-string node. `JacksonException` is unchecked now, so `catch (JsonProcessingException)` blocks stop compiling.
- **Testcontainers 2 renamed every artifactId** to a `testcontainers-` prefix (`junit-jupiter` -> `testcontainers-junit-jupiter`, `postgresql` -> `testcontainers-postgresql`). Java packages are unchanged, so no source edits — but `org.testcontainers.containers.PostgreSQLContainer` is now deprecated in favour of `org.testcontainers.postgresql.PostgreSQLContainer`.
- **JUnit 6** replaces JUnit 5. `org.junit.jupiter.api.Test`/`Assertions` are unchanged; `junit-vintage` is gone.
- **`@MockBean`/`@SpyBean` were removed.** Use `@MockitoBean`/`@MockitoSpyBean`.
- **Deprecated starter aliases:** `spring-boot-starter-oauth2-client` and `-oauth2-resource-server` are deprecated in favour of `spring-boot-starter-security-oauth2-client` / `-security-oauth2-resource-server`.
- **`application.yml` needed no changes.** Every key this project sets was checked against the Boot 4.0 and 4.1 configuration changelogs. If you add config, `spring-boot-properties-migrator` (runtime, optional) reports renamed keys at startup — add it for one boot cycle, then remove it.
- **Automation:** `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` plus `org.openrewrite.java.jackson.UpgradeJackson_2_3` do most of the mechanical work. Neither knows about the `spring-boot-starter-aspectj` requirement above.

## 9. Local infrastructure without Docker (WSL Ubuntu)

When Docker is not available, run PostgreSQL 16 + pgvector and Redis inside WSL Ubuntu and keep the backend and Vite on Windows. `scripts/wsl-infra-setup.sh` installs `postgresql-16`, `postgresql-16-pgvector` and `redis-server` (it asks for your sudo password), moves Postgres to **port 5433** (so it never collides with a Windows Postgres on 5432), creates the `codelensai` and `codelensai_it` databases, and sets Redis to `maxmemory-policy volatile-lru`.

```
wsl -d Ubuntu -- bash scripts/wsl-infra-setup.sh
```

WSL stops an idle distro after a while; keep it alive while you work (e.g. a terminal running `wsl -d Ubuntu -- bash -c "sudo service postgresql start; sudo service redis-server start; exec sleep infinity"`). Point the backend at it:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/codelensai
SPRING_REDIS_HOST=127.0.0.1
SPRING_DOCKER_COMPOSE_ENABLED=false     # stop spring-boot-docker-compose from running `docker compose up`
```

## 10. Demo profile, tests, E2E and eval

**Demo** (seeded users, repository and five PRs; password-less login; offline LLM paced so streaming is visible; no GitHub app or API key):

```
scripts\run-demo-backend.ps1                        REM Windows + WSL infra (sets the env vars above)
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run  # anywhere else
cd frontend && npm run dev                          # http://localhost:5173 -> "Demo as Alice"
```

The demo login/reset endpoints exist only under the `demo` profile, accept loopback callers only, and `DemoSafetyGuard` refuses to start the profile together with `prod` or a real GitHub OAuth client id. `POST /api/demo/reset` re-seeds from scratch.

**Integration tests** (`*IT`, Failsafe) use Testcontainers (`pgvector/pgvector:pg16`, `redis:7`). Without Docker they *abort* locally; with `CODELENS_REQUIRE_DOCKER=true` (CI) they *fail*. To run them against the WSL infra instead:

```
CODELENS_IT_EXTERNAL_INFRA=true SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/codelensai_it SPRING_REDIS_HOST=127.0.0.1 ./mvnw -B verify
# one IT only:  ... ./mvnw -B verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ReviewPipelineIT
```

**Frontend**: `npm run lint && npm run test && npm run build` (Vitest excludes `e2e/`).

**Playwright E2E** against a running demo-profile backend: `npm run e2e` builds the SPA and serves it with `vite preview` on :4173 (same `/api` + `/ws` proxy as dev); global setup resets the demo data and saves Alice's and Bob's sessions. `npm run e2e:screenshots` regenerates `docs/images/*.png`. First time: `npx playwright install chromium`.

**Eval** (needs `CODELENS_INTERNAL_ENABLED=true`, which the demo profile sets): `python eval/run_eval.py --ci --ablate-retrieval`; harness unit tests: `python -m pytest eval/test_scoring.py`. After changing cases in `eval/build_testset.py`, run it to regenerate `testset.jsonl`.

## 11. Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | `localhost:5432/codelensai`, `postgres`/`postgres` | PostgreSQL (must have pgvector) |
| `SPRING_REDIS_HOST` / `SPRING_REDIS_PORT` | `localhost` / `6379` | Redis |
| `SPRING_PROFILES_ACTIVE` | — | `demo` for the seeded demo; `prod` for deployments |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | placeholders | GitHub OAuth app |
| `GITHUB_WEBHOOK_SECRET` | `dev-webhook-secret` | global webhook HMAC secret (repos connected in the UI get their own) |
| `LLM_PROVIDER` | `local` | `local` · `gpt5` · `claude` |
| `LLM_FALLBACK_PROVIDER` | `local` | serves reviews when the primary fails or its breaker is open |
| `LLM_DAILY_TOKEN_BUDGET` | `2000000` | UTC-day token cap (not applied to zero-price models) |
| `ANTHROPIC_API_KEY`, `CLAUDE_REVIEW_MODEL`, `CLAUDE_SUMMARY_MODEL`, `CLAUDE_EFFORT` | —, `claude-opus-5-5`, `claude-opus-5-5`, `high` | Claude provider |
| `OPENAI_API_KEY`, `REVIEW_MODEL`, `SUMMARY_MODEL` | —, `gpt-5`, `gpt-5-mini` | OpenAI provider |
| `REVIEW_PROMPT_VERSION` | `v2` | `prompts/review_<version>.txt` |
| `CODELENS_EMBEDDING_PROVIDER` | `hashing` | `hashing` (offline) · `openai` (`text-embedding-3-small`, 512 dims) |
| `CODELENS_RETRIEVAL_ENABLED` / `CODELENS_INDEXING_ENABLED` | `true` / `true` | G1 kill switches |
| `CODELENS_INTERNAL_ENABLED` / `CODELENS_INTERNAL_TOKEN` | `false` / — | eval API `/api/internal/**` (404 when disabled; `X-Internal-Token` when set) |
| `CODELENS_ALLOWED_ORIGINS` | localhost dev ports | WebSocket handshake origins |
| `CODELENS_LOCAL_PACE_MS` | `0` (`700` in demo) | delay between the offline provider's events |
| `CODELENS_ENCRYPTION_SECRET` | dev value | AES-GCM key for stored GitHub tokens |
