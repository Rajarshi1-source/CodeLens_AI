# CodeLens AI — Migration Plan: Spring Boot 3.5.14 → 4.1.1

**Status:** proposed, not yet executed
**Baseline commit:** `4b37a3e` ("Full MVP complete.")
**Author:** drafted 2026-09-13
**Local toolchain verified:** JDK 21.0.11, Maven wrapper 3.9.16, Node 22 (CI)

---

## 0. Scope and verification note

Everything in this plan was checked against the live registries rather than taken
from release notes or a search summary. Where a version appears below, it was
resolved from one of:

- `repo1.maven.org` — `maven-metadata.xml` and the resolved POM/JAR contents
- `registry.npmjs.org` — `dist-tags`, `peerDependencies`, `engines`
- GitHub Advisory DB (`api.github.com/advisories`) — `first_patched_version`

**Confirmed:** Spring Boot **4.1.1** is the newest GA release (4.2.0-M1 is a
milestone, 4.0.8 is the newest 4.0 patch, 3.5.16 is the last 3.5 patch — the
3.5 line is EOL). Its BOM pins exactly what the brief said, plus several things
the brief did not mention that matter a great deal here:

| Managed dependency | Boot 3.5.14 (now) | Boot 4.1.1 (target) | Impact on this repo |
|---|---|---|---|
| Spring Framework | 6.2.x | **7.0.9** | transitive |
| Spring Security | 6.x | **7.1.1** | `SecurityConfig` — verified API-compatible |
| Hibernate ORM | 6.6.x | **7.4.5.Final** | entities are plain `jakarta.persistence` — low risk |
| Hibernate Validator | 8.x | **9.1.3.Final** | transitive |
| Tomcat | 10.1.x | **11.0.24** | transitive |
| Spring Data | 2025.0.x | **2026.0.1** (modules 4.1.1) | Redis Streams API — verify at compile |
| **Jackson** | **2.x** (`com.fasterxml.jackson.databind`) | **3.1.5** (`tools.jackson.databind`) | **7 source files break** |
| **JUnit** | **5.12.x** | **6.0.3** | package unchanged; runner/engine changes |
| **Testcontainers** | **1.20.x** | **2.0.5** | **artifactIds renamed** |
| Mockito | 5.x | 5.23.0 | not used |
| Lettuce | 6.x | 7.5.2.RELEASE | transitive |
| PostgreSQL driver | 42.7.x | 42.7.13 | transitive |
| Micrometer | 1.14.x | 1.17.1 | transitive |
| Reactor | 2024.0.x | 2025.0.7 | transitive |

Java 21 stays. Boot 4.1.1 supports Java 17–26; there is **no reason to move off
21 LTS**, and the existing `java.version` property is correct as-is.

---

## 1. What actually breaks (risk register)

Ordered by how much work each item is, not by severity.

### R1 — Jackson 2 → Jackson 3 (certain break, 7 files)

Boot 4's `spring-boot-starter-jackson` depends on `tools.jackson.core:jackson-databind`
(verified: the 4.1.1 POM for `spring-boot-jackson` lists exactly that). Databind and
core classes moved package:

- `com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper`
- `com.fasterxml.jackson.databind.JsonNode` → `tools.jackson.databind.JsonNode`
- **Annotations did not move** — `jackson-annotations` stays at
  `com.fasterxml.jackson.core:jackson-annotations` with package
  `com.fasterxml.jackson.annotation.*`. Nothing to do for `@JsonProperty` etc.
- Checked exceptions are gone: Jackson 3's `JacksonException` is unchecked. Any
  `catch (JsonProcessingException e)` becomes an "exception is never thrown" compile
  error.

Affected files (all confirmed by grep):

| File | Usage |
|---|---|
| `codelensai/src/main/java/com/codelensai/config/LlmConfig.java` | `ObjectMapper` injection point |
| `codelensai/src/main/java/com/codelensai/service/llm/OpenAiGpt5Provider.java` | `ObjectMapper`, `JsonNode` |
| `codelensai/src/main/java/com/codelensai/service/WebhookService.java` | `ObjectMapper.readTree`, `JsonNode` |
| `codelensai/src/main/java/com/codelensai/websocket/ReviewRelay.java` | `ObjectMapper.readValue` |
| `codelensai/src/main/java/com/codelensai/websocket/WebSocketNotifier.java` | `ObjectMapper.writeValueAsString` |

**Good news:** all three call sites already `catch (Exception e)` — no checked-exception
fallout. This is an import-only change at every site.

**Spring wiring:** Boot 4 auto-configures `tools.jackson.databind.json.JsonMapper`.
`JsonMapper extends ObjectMapper` in Jackson 3, so injecting
`tools.jackson.databind.ObjectMapper` (the only change needed in `LlmConfig`) resolves
against the auto-configured bean.

### R2 — Testcontainers 1.x → 2.0.5 (certain break, `pom.xml` only)

Testcontainers 2.x renamed every Maven artifact to a `testcontainers-` prefix
(verified against `testcontainers-bom:2.0.5`):

- `org.testcontainers:junit-jupiter` → `org.testcontainers:testcontainers-junit-jupiter`
- `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`

**Java packages are preserved.** `org.testcontainers.containers.PostgreSQLContainer`,
`org.testcontainers.containers.GenericContainer`, `org.testcontainers.utility.DockerImageName`
and `org.testcontainers.junit.jupiter.Testcontainers` all still exist in 2.0.5 (verified by
listing the JARs). `@Testcontainers(disabledWithoutDocker = true)` still compiles —
`javap` confirms the attribute is still on the annotation. So
`TestcontainersConfiguration.java` and the three IT classes need **no source change**;
only the two `<artifactId>`s in `pom.xml`.

There is a newer `org.testcontainers.postgresql.PostgreSQLContainer` package and an
`@EnabledIfDockerAvailable` annotation. Adopting them is optional polish, deliberately
**out of scope** for this migration.

### R3 — `spring-boot-starter-aop` was removed (certain break, runtime)

Boot 4.1.1's BOM has **zero** references to `spring-boot-starter-aop`; it is replaced by
`spring-boot-starter-aspectj` (`spring-boot-starter` + `spring-aop` + `aspectjweaver`).

This matters because `resilience4j-spring-boot4:2.4.0` does **not** pull an AOP starter
(its POM lists only `resilience4j-spring6`, `resilience4j-micrometer`, `slf4j-api`,
`spring-core`, `spring-context`, `spring-boot-autoconfigure`) — unlike
`resilience4j-spring-boot3`, which did. Without `spring-boot-starter-aspectj` on the
classpath, `@CircuitBreaker` / `@Retry` / `@Bulkhead` become **silent no-ops**: the app
starts, tests pass, and resilience quietly disappears.

Affected annotations: `AIReviewService` (llm) and `GitHubService` (github, 3 methods).

**Mitigation:** add `spring-boot-starter-aspectj` explicitly, and add a test that proves
the circuit breaker still opens (see §7, T5). This is the single most dangerous item in
the migration because its failure mode is invisible.

### R4 — Resilience4j needs the Boot 4 flavour (certain break, `pom.xml`)

`resilience4j-spring-boot3:2.2.0` targets Spring Framework 6. Use
`io.github.resilience4j:resilience4j-spring-boot4:2.4.0` — verified to exist and to
compile against `spring-core:7.0.2` / `spring-boot-autoconfigure:4.0.0`. `resilience4j-reactor`
moves 2.2.0 → 2.4.0 in lockstep. The `resilience4j:` block in `application.yml` is
unchanged between 2.2 and 2.4.

Still not BOM-managed — keep the explicit `${resilience4j.version}` pin.

### R5 — Starter renames (not breaking, but worth doing now)

Boot 4 split the monolithic starters. Old names still resolve, but two are explicitly
deprecated in their own POM `<description>`:

| Current | Boot 4 replacement | Note |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | old one still present, identical contents |
| `spring-boot-starter-webflux` | **`spring-boot-starter-webclient`** | see below — real win |
| `spring-boot-starter-oauth2-client` | `spring-boot-starter-security-oauth2-client` | POM says *"deprecated in favor of"* |
| `spring-boot-starter-oauth2-resource-server` | `spring-boot-starter-security-oauth2-resource-server` | same |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` | **removed**, see R3 |

`spring-boot-starter-webclient` is the correct dependency for this codebase. The project
pulled in the whole reactive web server stack purely to get `WebClient` and `Flux`. I
verified **no controller returns `Flux`/`Mono`** — reactive types appear only in
`AIReviewService.stream()`, `GitHubService` (`bodyToMono`), and the LLM providers, all
consumed internally and pushed out over STOMP. `spring-boot-starter-webclient` gives
`spring-boot-webclient` + `spring-boot-reactor` + `reactor-netty-http` without
`spring-boot-webflux`, which removes the "is this app MVC or reactive?" ambiguity the
old skill docs had to explain away.

### R6 — JUnit 5 → JUnit 6 (low risk)

`junit-jupiter:6.0.3`. `org.junit.jupiter.api.Test` and `org.junit.jupiter.api.Assertions`
are unchanged, and all four test classes use only those. JUnit 6 requires Java 17+ (we
have 21). `junit-vintage` is gone — not used here. Expect zero source changes; the
Surefire version comes from the Boot parent.

### R7 — `@MockBean` / `@SpyBean` removed (no impact)

Confirmed absent from `spring-boot-test:4.1.1` (0 matching classes). Also confirmed
**this repo never uses them** — no mocks in any test. Nothing to do, but noted because
OpenRewrite will look for them.

### R8 — `application.yml` (no changes expected)

I checked every property key this app sets against OpenRewrite's
`spring-boot-40-properties.yml` and `spring-boot-41-properties.yml`, which are generated
from the official Boot configuration changelogs (96 renames in 4.0). **None of the keys
in `application.yml` appear in either file.** The only `spring.jpa.*` rename is
`spring.jpa.hibernate.naming.implicit-strategy`, which we do not set.

Verified-clean keys include: `spring.threads.virtual.enabled`, `spring.sql.init.*`,
`spring.jpa.*`, `spring.datasource.*`, `spring.data.redis.*`,
`spring.security.oauth2.client.registration.github.*`, `server.shutdown`,
`server.forward-headers-strategy`, `management.endpoints.web.exposure.include`,
`management.endpoint.health.*`, `management.metrics.tags.*`, `spring.config.import`.

Still add `spring-boot-properties-migrator` for one boot cycle as a runtime safety net
(verified to exist at 4.1.1), then remove it.

### R9 — Spring Security 7 (low risk, verify)

Every API `SecurityConfig` uses was confirmed present in Security 7.1.1 by listing the
JARs:

- `AbstractHttpConfigurer` ✓ (`spring-security-config` 7.1.1)
- `PathPatternRequestMatcher` ✓ same package `org.springframework.security.web.servlet.util.matcher`
- `HttpStatusEntryPoint` ✓ (`spring-security-web` 7.1.1)

`csrf`, `authorizeHttpRequests`, `exceptionHandling`, `oauth2Login`, `logout` are all
lambda-DSL calls, which is the only style Security 7 accepts — the config is already
written in the post-6.1 idiom. Expect a clean compile. The one thing to re-test manually
is the **OAuth2 redirect round-trip** (§7, T7), because Security 7 tightened redirect and
`RequestCache` defaults.

### R10 — Spring Data Redis 4.1.1 / Lettuce 7 (verify at compile)

`ReviewJobConsumer` uses a broad slice of the Streams API:
`opsForStream().createGroup/read/pending/claim/add/acknowledge`, `Consumer.from`,
`StreamOffset`, `ReadOffset`, `StreamReadOptions`, `StreamRecords.mapBacked`,
`MapRecord`, `PendingMessages`, plus `org.springframework.data.domain.Range`.
`RedisConfig` uses `RedisMessageListenerContainer` + `PatternTopic`.

None of these are known removals, but Spring Data 4.x is a major and `Range`'s factory
methods have churned before. **Treat this file as the compile-error hotspot** after the
POM bump. If `Range` breaks, the fix is mechanical (`Range.closed(a, b)` /
`Range.from(Bound.inclusive(a)).to(Bound.inclusive(b))`).

### R11 — Hibernate 7.4 (low risk)

Entities use only `jakarta.persistence.*` plus `@JdbcTypeCode(SqlTypes.JSON)` on
`ReviewCommentEntity`, which Hibernate 7 still supports. JPQL in the three `@Query`
methods is ordinary (including the fully-qualified enum literal in
`ReviewSessionRepository`). `ddl-auto: none` with `schema.sql` means no schema
regeneration surprises. Run the Postgres ITs to confirm.

---

## 2. Migration route

Do **not** jump 3.5.14 → 4.1.1 in one commit. Three steps, each independently
compilable and testable, each its own commit:

```
3.5.14  ──▶  3.5.16  ──▶  4.0.8  ──▶  4.1.1
   (a)          (b)         (c)
```

- **(a) 3.5.14 → 3.5.16** — last 3.5 patch. Pure patch bump, flushes out anything that
  3.5's own deprecation warnings can tell us before the majors land. Cheap.
- **(b) 3.5.16 → 4.0.8** — the real migration: Framework 7, Security 7, Jackson 3,
  Hibernate 7, JUnit 6, Testcontainers 2, modular starters. This is where all of §1
  happens.
- **(c) 4.0.8 → 4.1.1** — minor bump within Boot 4. Parent version + `SpringBootProperties_4_1`.

Rationale: if something breaks at (c), we know it is a 4.0→4.1 issue and not one of the
thirty things that changed at (b).

### OpenRewrite does most of (b) mechanically

Verified available today:

| Recipe | Artifact | Covers |
|---|---|---|
| `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` | `rewrite-spring:6.37.1` | Framework 7, Security 7, `MigrateToModularStarters`, `RenameDeprecatedStartersManagedVersions`, `MigrateJacksonBomProperty`, `MigrateToHibernate71`, `Testcontainers2Migration`, `ReplaceMockBeanAndSpyBean`, parent bump |
| `org.openrewrite.java.jackson.UpgradeJackson_2_3` | `rewrite-jackson:1.29.0` | `com.fasterxml.jackson.databind` → `tools.jackson.databind` type + method renames |
| `org.openrewrite.java.spring.boot4.SpringBootProperties_4_1` | `rewrite-spring:6.37.1` | step (c) property changelog |

Note: there is **no** `UpgradeSpringBoot_4_1` composite recipe in `rewrite-spring:6.37.1`
yet — only the `_4_1` properties recipe. So step (c)'s parent bump is a manual one-liner.

Run OpenRewrite in **dry-run first**, read the patch, then apply. It will not know about
R3 (`spring-boot-starter-aspectj` for Resilience4j) or R4 (`resilience4j-spring-boot4`) —
those are ours.

```bash
cd codelensai && ./mvnw -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-spring:6.37.1,org.openrewrite.recipe:rewrite-jackson:1.29.0 -Drewrite.activeRecipes=org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0,org.openrewrite.java.jackson.UpgradeJackson_2_3
```

---

## 3. Phase 0 — baseline and branch

1. Branch: `git checkout -b migrate/spring-boot-4.1.1`
2. Capture a green baseline **before touching anything**:
   ```bash
   cd codelensai && ./mvnw -B clean verify
   ```
   ```bash
   cd frontend && npm ci && npm run lint && npm run test && npm run build
   ```
   *(Backend `test-compile` was already confirmed clean at `4b37a3e`.)*
3. Record the eval gate's baseline numbers so §7 T9 has something to compare against:
   ```bash
   cd codelensai && ./mvnw -B -DskipTests package && java -jar target/*.jar
   ```
   ```bash
   CODELENS_BASE_URL=http://localhost:8080 python eval/run_eval.py
   ```
4. Commit `docs/SPRING_BOOT_4_MIGRATION_PLAN.md` (this file) so the plan is on the branch.

**Gate:** do not proceed unless the baseline is green. A pre-existing failure attributed
to the migration will cost hours.

---

## 4. Phase 1 — backend `pom.xml`

Target state for `codelensai/pom.xml`. Changes are marked.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.1</version>                          <!-- CHANGED from 3.5.14 -->
  <relativePath/>
</parent>

<properties>
  <java.version>21</java.version>                   <!-- unchanged: 4.1.1 supports 17–26 -->
  <resilience4j.version>2.4.0</resilience4j.version> <!-- CHANGED from 2.2.0 -->
</properties>
```

Dependency changes:

| Action | Coordinates | Why |
|---|---|---|
| keep | `spring-boot-starter-actuator` | pulls `spring-boot-starter-micrometer-metrics` in 4.x; `/actuator/prometheus` still works with the Micrometer registry below |
| keep | `spring-boot-starter-data-jpa` | |
| keep | `spring-boot-starter-data-redis` | |
| **rename** | `spring-boot-starter-oauth2-client` → `spring-boot-starter-security-oauth2-client` | deprecated alias |
| **rename** | `spring-boot-starter-oauth2-resource-server` → `spring-boot-starter-security-oauth2-resource-server` | deprecated alias |
| keep | `spring-boot-starter-security` | |
| keep | `spring-boot-starter-validation` | |
| **rename** | `spring-boot-starter-web` → `spring-boot-starter-webmvc` | canonical 4.x name |
| **replace** | `spring-boot-starter-webflux` → `spring-boot-starter-webclient` | R5 — no reactive controllers exist |
| keep | `spring-boot-starter-websocket` | |
| **ADD** | `spring-boot-starter-aspectj` | **R3 — required or Resilience4j silently no-ops** |
| **change** | `resilience4j-spring-boot3` → `resilience4j-spring-boot4` @ `2.4.0` | R4 |
| **bump** | `resilience4j-reactor` → `2.4.0` | lockstep |
| keep | `spring-boot-devtools`, `spring-boot-docker-compose` (runtime/optional) | both present in 4.1.1 BOM |
| keep | `io.micrometer:micrometer-registry-prometheus` (runtime) | version via `micrometer-bom` 1.17.1 |
| keep | `org.postgresql:postgresql` (runtime) | 42.7.13 |
| keep | `org.projectlombok:lombok` (optional) | |
| **ADD (temporary)** | `spring-boot-properties-migrator` (runtime, optional) | R8 safety net — **remove before merge** |
| keep | `spring-boot-starter-test` (test) | now JUnit 6 + Mockito 5.23 |
| keep | `spring-boot-testcontainers` (test) | |
| keep | `reactor-test`, `spring-security-test` (test) | |
| **rename** | `org.testcontainers:junit-jupiter` → `org.testcontainers:testcontainers-junit-jupiter` | R2 |
| **rename** | `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql` | R2 |

Build plugins: the `spring-boot-maven-plugin` Lombok `<exclude>` and the two
`maven-compiler-plugin` `annotationProcessorPaths` executions stay as they are. Nothing
in Boot 4 changes that wiring.

**Checkpoint P1:** `./mvnw -B clean test-compile` — expect the R1 Jackson import errors
and possibly R10 (`Range`). Nothing else.

---

## 5. Phase 2 — backend source changes

Ordered so each step ends compilable.

### 5.1 Jackson imports (R1)

Mechanical, 7 import lines across 5 files. If OpenRewrite's
`UpgradeJackson_2_3` ran, verify rather than rewrite:

```
com.fasterxml.jackson.databind.ObjectMapper  →  tools.jackson.databind.ObjectMapper
com.fasterxml.jackson.databind.JsonNode      →  tools.jackson.databind.JsonNode
```

Files: `config/LlmConfig.java`, `service/llm/OpenAiGpt5Provider.java`,
`service/WebhookService.java`, `websocket/ReviewRelay.java`,
`websocket/WebSocketNotifier.java`.

Then read each call site once, looking specifically for:
- `readTree` / `readValue` / `writeValueAsString` signature drift
- `JsonNode.asText()` / `.asLong()` / `.asInt()` — Jackson 3 renamed some
  `asXxx()` coercions; `WebhookService` uses six of them, so check compiler output
  carefully rather than assuming
- any `ObjectMapper` mutation (`configure(...)`, `setSerializationInclusion`) — Jackson 3
  mappers are immutable, built via `JsonMapper.builder()`. Grep says we have none, but
  confirm.

### 5.2 Resilience4j AOP proof (R3)

After adding `spring-boot-starter-aspectj`, prove the aspects are live. Cheapest proof:
start the app and assert the actuator exposes circuit-breaker state, or add the unit test
in §7 T5. **Do not skip this** — a passing build says nothing about whether the aspects
were woven.

### 5.3 Redis Streams / Spring Data 4 (R10)

Fix whatever `ReviewJobConsumer.java` and `RedisConfig.java` report. Likely nothing;
`Range` is the one to watch.

### 5.4 Optional cleanups (only if free)

- The `@SuppressWarnings("unchecked")` on `ReviewJobConsumer.read(...)` may become
  unnecessary if Spring Data 4 fixed the varargs generics. Remove it only if the
  compiler now warns that it is redundant.
- `SecurityConfig`'s Javadoc mentions the omitted `oauth2ResourceServer().jwt()` wiring
  as an MVP deviation. That is still accurate — **leave it alone.** This migration is not
  the place to change the auth model.

**Checkpoint P2:** `./mvnw -B clean test-compile` is green.

---

## 6. Phase 3 — config, Docker, CI

### 6.1 `application.yml`

No changes expected (R8). Add the properties-migrator, boot once, read the log, then
remove the dependency.

### 6.2 `codelensai/Dockerfile`

Only the comment is stale:

```diff
-# Multi-stage build for the CodeLens AI backend (Spring Boot 3.5.x / Java 21).
+# Multi-stage build for the CodeLens AI backend (Spring Boot 4.1.x / Java 21).
```

`eclipse-temurin:21-jdk` / `:21-jre` stay — Java 21 is still the target. The
`chmod +x mvnw` line stays (it fixes the Windows-checkout mode bit).

### 6.3 `codelensai/HELP.md`

Every `docs.spring.io/spring-boot/3.5.14/...` link is now a dead version path. Bulk
replace `3.5.14` → `4.1.1`. Two link targets changed shape in the 4.x docs and should
be re-pointed by hand rather than string-replaced:
- "Spring Reactive Web" → the WebClient reference (we no longer use reactive web)
- "Spring Web" → `reference/web/webmvc.html`

### 6.4 `.github/workflows/ci.yml`

JDK stays 21, Node stays 22. No mandatory changes. Two worth making:

1. Add a `SPRING_PROFILES_ACTIVE`-free smoke assertion after the eval gate's backend
   boot that greps `backend.log` for `Circuit breaker` registration, so R3 cannot
   regress silently in CI.
2. `actions/setup-java@v4` and `actions/checkout@v4` are fine; leave the version pins
   alone in this PR to keep the diff attributable.

### 6.5 `docker-compose.yml` / `codelensai/compose.yaml`

`postgres:16-alpine` and `redis:7-alpine` are compatible with the driver/Lettuce bumps.
**No change.** (Postgres 17/18 and Redis 8 are a separate decision; do not bundle them
here — they would confound the migration's test results.)

---

## 7. Phase 4 — test plan (backend)

Run the full suite, then these targeted checks. The existing suite is thin (4 test
classes), so the migration's real verification is mostly manual + the eval gate.

| # | Check | How | Why it matters for *this* migration |
|---|---|---|---|
| T1 | Unit tests green | `./mvnw -B test` | JUnit 6 runner works; `DiffChunkerServiceTest`, `LocalModelProviderTest` |
| T2 | Context loads | `CodelensaiApplicationTests` | Framework 7 + Security 7 + Hibernate 7 all wire together |
| T3 | Postgres ITs green | `ReviewSessionIdempotencyIT` (needs Docker) | Hibernate 7 + Spring Data JPA 4 + Testcontainers 2 |
| T4 | Redis idempotency green | `WebhookIdempotencyTest` | Lettuce 7 + Spring Data Redis 4 |
| T5 | **Resilience4j aspects woven** | new test: force `LlmReviewProvider` to throw, assert `fallback` ran / breaker transitioned | **R3 — the silent failure mode** |
| T6 | Redis Streams round-trip | `ReviewQueueService.enqueue` → `ReviewJobConsumer` consumes + acks | R10; currently untested, and the most API-exposed file |
| T7 | GitHub OAuth2 round-trip | manual: `docker compose up`, visit `:3000`, log in, land authenticated | R9 — Security 7 redirect/RequestCache defaults, `forward-headers-strategy: framework` behind nginx |
| T8 | STOMP/SockJS stream | manual: open a PR review page, confirm tokens stream in | WebSocket + Redis Pub/Sub (`ReviewRelay` JSON now goes through Jackson 3) |
| T9 | Eval gate no worse than baseline | `python eval/run_eval.py --ci` | catches Jackson 3 serialization drift in `ReviewComment` JSON — the subtlest R1 risk |
| T10 | `/actuator/health`, `/actuator/prometheus` | `curl` both | actuator restructure in 4.x; Prometheus registry still registered |
| T11 | No properties-migrator warnings | read boot log once with the migrator on | R8 |

T5, T6, and T9 are the ones that would actually catch a real regression here. T5 and T6
are new tests worth keeping permanently.

---

## 8. Phase 5 — frontend: Dependabot remediation

The frontend carries **34 open alerts**. Every one was resolved to a concrete
`first_patched_version` from the GitHub Advisory DB. Two findings reframe the work:

1. **Almost all of it is transitive.** From `package-lock.json`, only `vite`, `vitest`,
   and `react-router-dom` are direct; `brace-expansion`, `js-yaml`, `postcss`,
   `browserslist`, `nanoid`, `esbuild`, `form-data`, `baseline-browser-mapping`,
   `launch-editor`, and `websocket-driver` are all pulled in by something else.
2. **One alert is a production dependency, not dev.** `websocket-driver@0.7.4` ships in
   the bundle via `sockjs-client` → `faye-websocket`, and carries a **critical** message-
   corruption advisory (GHSA-xv26-6w52-cph6). Every other frontend alert is
   `Development` scope. Fix this one first.

### 8.1 Required versions

| Package | Locked now | Needs ≥ | How it gets there |
|---|---|---|---|
| `websocket-driver` | 0.7.4 (**prod**) | **0.7.5** | `overrides` — `sockjs-client` has no newer release |
| `vite` | 5.4.21 | **7.3.5** | direct bump → `^7.3.6` |
| `vitest` + `@vitest/mocker` | 2.1.9 | **4.1.11** | direct bump → `^4.1.11` |
| `react-router` / `react-router-dom` | 6.30.4 (**prod**) | **7.18.2** | direct bump → `^7.18.3` (see 8.3) |
| `esbuild` | 0.21.5 | 0.28.1 | falls out of Vite 7 (`esbuild ^0.27.0 \|\| ^0.28.0`) |
| `postcss` | 8.5.15 | 8.5.23 | falls out of Vite 7 (`postcss ^8.5.6`) |
| `browserslist` | 4.28.2 | 4.28.7 | semver-compatible refresh via `npm update` |
| `baseline-browser-mapping` | 2.10.33 | 2.11.0 | same |
| `nanoid` | 3.3.12 | 3.3.18 | same |
| `js-yaml` | 4.2.0 | 4.3.2 | same (via `@eslint/eslintrc`) |
| `brace-expansion` | 1.1.15 / 5.0.6 | 1.1.18 / 5.0.9 | same |
| `form-data` | 4.0.5 | 4.0.6 | same |
| `launch-editor` | (via vite) | 2.14.1 | falls out of Vite 7 |

### 8.2 Toolchain target — Vite 7, not Vite 8

This is the one real judgement call in the frontend work, and it goes against "newest".

Latest Vite is **8.3.0**, and `@vitejs/plugin-react@6.x` requires `vite ^8.0.0`. But
**Vite 8 replaced Rollup with Rolldown** (`vite@8.3.0` depends on `rolldown ~1.2.6`; no
`rollup`, no `esbuild`). `vite.config.ts` uses
`build.rollupOptions.output.manualChunks` to split four vendor chunks
(react / charts / ws / query) — exactly the API Rolldown deprecates in favour of
`output.advancedChunks`. Adopting Vite 8 means rewriting the chunking strategy and
re-validating bundle output, which has nothing to do with fixing CVEs.

**`@vitejs/plugin-react@5.2.0` supports `vite ^4.2 || ^5 || ^6 || ^7 || ^8`** — verified
from its `peerDependencies`. So Vite **7.3.6** + plugin-react **5.2.0** clears every Vite
and esbuild advisory, keeps Rollup, and leaves `manualChunks` untouched.

Vitest 4.1.11's peer range is `vite ^6 || ^7 || ^8` ✓, and its `engines` want Node
`^20 || ^22 || >=24` — CI's Node 22 is fine. Vite 7 needs Node `^20.19.0 || >=22.12.0`,
also fine.

Vite 8 is a reasonable follow-up ticket. It is not part of this migration.

### 8.3 React Router 6 → 7 is unavoidable

`react-router-dom@6.30.6` exists and patches GHSA-jjmj-jmhj-qwj2. But three other
advisories against `react-router` 6.x — GHSA-wrjc-x8rr-h8h6 (open redirect → XSS),
GHSA-337j-9hxr-rhxg, GHSA-h8fp-f39c-q6mh — list **`first_patched_version: 7.18.0`
with no 6.x backport**. Staying on v6 means permanently carrying those alerts.

The good news is that this codebase's router surface is tiny and entirely declarative.
Full inventory:

| File | Imports |
|---|---|
| `src/App.tsx` | `BrowserRouter`, `Navigate`, `Route`, `Routes` |
| `src/components/layout/AppShell.tsx` | `NavLink`, `Outlet` |
| `src/components/layout/RequireAuth.tsx` | `Navigate`, `useLocation` |
| `src/components/layout/RequireAuth.test.tsx` | `MemoryRouter`, `Route`, `Routes` |
| `src/routes/Login.tsx` | `Navigate` |
| `src/routes/PRList.tsx` | `useNavigate` |
| `src/routes/PRReview.tsx` | `Link`, `useParams` |

All ten of these exist unchanged in v7, which re-exports them from `react-router`. There
are no loaders, no `json()`/`defer()`, no data router, no `RouterProvider` — so the v6→v7
breaking-change list does not touch this app. `react-router-dom@7.x` peer is
`react >=18`, so **React stays at 18.3.1**.

`vite.config.ts`'s `manualChunks.react` array lists `'react-router-dom'` — still a valid
module ID in v7, no change needed.

### 8.4 What deliberately does *not* change

The brief said to touch React/TypeScript/Tailwind "only if absolutely necessary." It is
not necessary:

| Package | Latest | Keeping | Reason |
|---|---|---|---|
| `react` / `react-dom` | 19.3.0 | **18.3.1** | no advisory; `react-router-dom@7` accepts `>=18`; React 19 would also force `@types/react@19`, and `react-diff-viewer-continued@3.4` peers only up to React 18 (would need a 4.x bump too) |
| `typescript` | 7.0.2 | **^5.9.3** (from 5.6.3) | `typescript-eslint@8.70` peers `typescript >=4.8.4 <6.1.0` — TS 6/7 would force an ESLint-stack migration for zero security benefit |
| `tailwindcss` / `@tailwindcss/vite` | 4.3.3 | **`^4.0.0` unchanged** | the caret already floats to 4.3.3; `@tailwindcss/vite` peers `vite ^5 \|\| ^6 \|\| ^7 \|\| ^8` ✓. shadcn/ui components are vendored in `src/components/ui/` and are unaffected |
| `eslint` | 10.10.0 | **^9.15.0** | `typescript-eslint@8.70` supports ESLint 8/9/10; staying on 9 keeps the flat config as-is and still resolves a patched `js-yaml` |
| `zod` | 4.6.4 | **^3.23.8** | zod 4 is a breaking rewrite; `src/types/schemas.ts` and every `services/*.ts` parse call would need review. No advisory. Separate ticket. |
| `recharts` | 3.10.1 | **^2.13.3** | v3 is breaking; `SeverityChart`/`ReviewCoverage` would need rework. No advisory. |
| `lucide-react`, `sonner`, `tailwind-merge`, `@hookform/resolvers`, `jsdom` | 1.45 / 2.0.8 / 3.7 / 5.9 / 30.0.1 | current majors | all breaking majors, none advisory-driven |

### 8.5 Concrete `package.json` diff

```diff
   "dependencies": {
-    "react-router-dom": "^6.28.0",
+    "react-router-dom": "^7.18.3",
   },
   "devDependencies": {
-    "@vitejs/plugin-react": "^4.3.4",
+    "@vitejs/plugin-react": "^5.2.0",
-    "typescript": "^5.6.3",
+    "typescript": "^5.9.3",
-    "typescript-eslint": "^8.16.0",
+    "typescript-eslint": "^8.70.0",
-    "vite": "^5.4.11",
+    "vite": "^7.3.6",
-    "vitest": "^2.1.6"
+    "vitest": "^4.1.11"
   },
-  "overrides": {
-    "vite": "^5.4.11"
-  }
+  "overrides": {
+    "websocket-driver": "^0.7.5"
+  }
```

The old `overrides.vite` pin **must go** — it would hold Vite at the vulnerable 5.x.

Then:

```bash
cd frontend && rm -rf node_modules package-lock.json && npm install && npm audit
```

Regenerate the lockfile rather than `npm update`, so the transitive tree is resolved
fresh against the new toolchain. Add further `overrides` entries only for whatever
`npm audit` still reports afterwards (candidates, with target floors: `brace-expansion@^1.1.18`,
`js-yaml@^4.3.2`, `postcss@^8.5.23`, `browserslist@^4.28.7`, `nanoid@^3.3.18`,
`baseline-browser-mapping@^2.11.0`, `form-data@^4.0.6`).

### 8.6 Frontend verification

| # | Check | Command / action |
|---|---|---|
| F1 | `npm audit` reports 0 high/critical | `npm audit` |
| F2 | Lint clean | `npm run lint` (ESLint 9 + typescript-eslint 8.70 + TS 5.9) |
| F3 | Typecheck clean | `tsc -b` — watch `tsconfig.app.json`'s `"types": ["vitest/globals", ...]`; if Vitest 4 renamed that entry, fix it here |
| F4 | Unit tests green | `npm run test` — 5 test files: `RequireAuth.test.tsx`, `PRFilters.test.ts`, `parseDiff.test.ts`, `reviewStream.test.ts`, `severity.test.ts` |
| F5 | Build green + chunks intact | `npm run build`; confirm the four `manualChunks` still emit as separate files |
| F6 | Dev proxy works | `npm run dev`; exercise `/api`, `/ws`, `/oauth2`, `/login`, `/logout` proxies |
| F7 | SockJS still works | confirm `define: { global: 'globalThis' }` still satisfies `sockjs-client` under Vite 7 in **both** dev and preview |
| F8 | Routing works | click through Login → PRList → PRReview → Dashboard → RepoSettings; verify `RequireAuth` redirect and the `NavLink` active states under Router 7 |
| F9 | Docker image builds | `docker build ./frontend` (node:22-alpine satisfies Vite 7's Node floor) |

F7 and F8 are the two that a green `npm run test` would not catch.

---

## 9. Phase 6 — `eval/requirements.txt`

Two `requests` advisories (`.netrc` credential leak, insecure temp-file reuse in
`extract_zipped_paths()`), both `first_patched_version: 2.33.0`.

```diff
-requests==2.32.3
+requests==2.34.2
```

2.34.2 is the current PyPI release. `eval/reviewer_client.py` uses plain
`requests.post`/`get`, so there is no API risk. Re-run `python eval/run_eval.py --ci`
against the migrated backend (T9) to confirm.

---

## 10. Phase 7 — update the Claude skills

Four skills live at `.claude/skills/codelens-skills/`. Only **two** need changes; the
React/TypeScript and Tailwind skills stay as they are, because §8.4 keeps React 18,
TS 5.x, and Tailwind 4 exactly where they are.

There is also a **duplicate** of the Spring build reference at the repo root
(`build-and-run.md`, byte-identical in intent to
`.claude/skills/codelens-skills/codelens-spring-boot/references/build-and-run.md`).
Both must be updated or the root copy deleted — otherwise the stale one will be read
back as authoritative later.

### 10.1 `codelens-spring-boot/SKILL.md`

| Line | Now | Change to |
|---|---|---|
| 3 (`description`) | "Spring Boot 3.5.x specialist…", "Spring Security 6 GitHub OAuth2" | "Spring Boot 4.1.x specialist…", "Spring Security 7 GitHub OAuth2" |
| 6 (title) | `# CodeLens AI — Spring Boot 3.5.x (Java 21)` | `# CodeLens AI — Spring Boot 4.1.x (Java 21)` |
| 8 | "Spring Boot 3.5.x … latest supported 3.x line — still Spring Framework 6.2 / Spring Security 6, so the patterns here are unchanged from earlier 3.x" | "Spring Boot 4.1.x on Spring Framework 7.0 / Spring Security 7.1. Java 21 is fully supported (4.1.x tests 17–26)." |
| 20 (reference table) | "(Maven, Spring Boot 3.5.x, Java 21)" | "(Maven, Spring Boot 4.1.x, Java 21)" |
| 140 (§5 heading) | `## 5. Spring Security 6 — GitHub OAuth2 + stateless API` | `## 5. Spring Security 7 — GitHub OAuth2 + stateless API` |
| 189 | "`resilience4j-spring-boot3` dependency is not managed by the Spring Boot BOM" | "`resilience4j-spring-boot4` … still not BOM-managed; also requires `spring-boot-starter-aspectj` on Boot 4, because the starter no longer brings AOP transitively" |

**This reverses a decision recorded earlier.** The note "I deliberately kept every
Spring Security 6 reference as-is — 3.5.x runs on Spring Framework 6.2 / Security 6, so
those are still correct and would only need revisiting if you later jump to Boot 4.0
(Security 7)" was right at the time. This migration is exactly that jump, so every
Security 6 reference now has to move to 7. Same for the
"Boot 4.0 … not worth the migration for this build" rationale in
`references/build-and-run.md` line 18 — replace it with the reason we *did* migrate
(3.5.x reached OSS EOL on 2026-06-30 at 3.5.16).

### 10.2 `codelens-spring-boot/references/build-and-run.md` (+ the root duplicate)

- Title and intro: 3.5.x → 4.1.x
- Locked-decisions section: rewrite the "Spring Boot 3.5.x, not 3.3" bullet as
  "Spring Boot 4.1.x — 3.5.x went OSS-EOL at 3.5.16 on 2026-06-30; 4.1.x is current GA
  on Framework 7 / Security 7, Java 17–26"
- Initializr dependency list: `webflux` → `webclient` (or note that WebClient now has its
  own starter), and add the AspectJ starter
- `curl.exe` one-liner: `bootVersion=3.5.11` → `4.1.1`
- The full `pom.xml` block: replace with the Phase 1 target from §4 of this document
- "With both `spring-boot-starter-web` and `spring-boot-starter-webflux` present, Spring
  Boot runs as a servlet (MVC) app…" (line ~227) — **delete this paragraph.** It is the
  explanation for a workaround we are removing. Replace with: `spring-boot-starter-webmvc`
  for the servlet stack, `spring-boot-starter-webclient` for `WebClient` + `Flux`; no
  ambiguity to explain.
- "Tests: JUnit 5, Mockito, Security test, Testcontainers" (line ~145) → "JUnit 6,
  Mockito, Security test, Testcontainers 2" with the renamed `testcontainers-*`
  artifactIds

### 10.3 `codelens-java21/SKILL.md`

| Line | Change |
|---|---|
| 8 | "The backend is **Spring Boot 3.5.x (Java 21)**" → 4.1.x |
| 134 | `## Testing (JUnit 5 + Mockito + TestContainers)` → `JUnit 6` |
| 154 | "Use the latest **Spring Boot 3.5.x** patch — it's the newest supported 3.x line, still Spring Framework 6.2 / Spring Security 6" → rewrite for 4.1.x / Framework 7 / Security 7 |
| 161 | `<version>3.5.11</version>` → `<version>4.1.1</version>` |
| 176 | `spring-boot-starter-webflux` (WebClient + Flux streaming) → `spring-boot-starter-webclient` |
| ~200 | Testcontainers block → renamed artifactIds |

Everything else in this skill is version-agnostic and stays. Specifically **keep**:
- the "no preview features / no `--enable-preview`" mandate — still true on Java 21
- the `StructuredTaskScope`-is-preview explanation in `references/concurrency.md` —
  still accurate for Java 21, and the `absent`-type eval assertions depend on it
- `references/domain-types.md` — no version references

### 10.4 `codelens-react-typescript` and `codelens-tailwind-shadcn`

**No changes.** Verified against §8.4: React stays 18, TypeScript stays 5.x, Tailwind
stays 4.x, shadcn/ui stays vendored. Check them only for a hard-pinned
`react-router-dom` 6.x version string; if one exists, bump it to 7.x. Otherwise leave
both skills and all four of their reference files untouched.

### 10.5 Skill consistency gate

After editing, re-run the checks that were used when the bundle was first built:

```bash
cd .claude/skills/codelens-skills && for f in */evals/evals.json; do python -c "import json,sys;json.load(open('$f'))" && echo "OK $f"; done
```

```bash
cd .claude/skills/codelens-skills && grep -rn "3\.5\.\|Security 6\|Framework 6\|spring-boot-starter-webflux\|resilience4j-spring-boot3\|JUnit 5\|starter-aop" . ; echo "--- any hit above must be a deliberate 'why we moved off this' explanation"
```

Also grep the repo root docs the same way — `build-and-run.md`, `README.md`,
`codelens_starter_code.md`, `CodeLens_AI_Implementation_Plan_v2_MASTER.md`, and
`codelensai/HELP.md` all carry 3.5.x strings.

---

## 11. Commit sequence

One commit per row; each must compile.

| # | Commit | Contents |
|---|---|---|
| 1 | `docs: add Spring Boot 4.1.1 migration plan` | this file |
| 2 | `build: bump Spring Boot 3.5.14 → 3.5.16` | parent version only; full suite green |
| 3 | `build: migrate to Spring Boot 4.0.8 (pom)` | §4 POM changes incl. aspectj + resilience4j 2.4.0 + testcontainers renames |
| 4 | `refactor: migrate Jackson 2 → Jackson 3 imports` | §5.1 |
| 5 | `fix: Spring Data Redis 4 / Security 7 compile fixes` | §5.3, §5.2 — only if the compiler demands them |
| 6 | `test: cover resilience4j aspects and Redis Streams round-trip` | T5, T6 |
| 7 | `build: bump Spring Boot 4.0.8 → 4.1.1` | parent version + `SpringBootProperties_4_1` |
| 8 | `chore: drop properties-migrator; update Dockerfile/HELP version strings` | §6.2, §6.3, remove temp dependency |
| 9 | `fix(frontend): clear Dependabot alerts — Vite 7, Vitest 4, Router 7` | §8.5 + regenerated lockfile |
| 10 | `fix(eval): bump requests to 2.34.2` | §9 |
| 11 | `docs(skills): retarget Spring/Java skills at Boot 4.1.x` | §10 |

Open one PR. Dependabot will auto-close the 34 frontend alerts and both `requests` alerts
once commits 9–10 land on `main`.

---

## 12. Rollback

Each phase is its own commit on `migrate/spring-boot-4.1.1`, so `git revert` of a single
commit is the unit of rollback. The two points where a clean abort is cheapest:

- **After commit 2** (3.5.16) — a safe, supported-for-nothing-but-still-working resting
  point if Boot 4 turns out to be more work than expected. 3.5.16 is OSS-EOL, so this is
  a pause, not a destination.
- **After commit 8** (backend on 4.1.1, frontend untouched) — the frontend work in
  commits 9–11 is fully independent and can ship separately.

The frontend change (commits 9–10) has **no dependency on the backend migration** and
could be shipped first as its own PR if the Dependabot alerts are more urgent than the
Boot upgrade. Given one alert is a critical in a *production* dependency
(`websocket-driver`), that reordering is defensible.

---

## 13. Open questions

1. **Vite 8 / Rolldown** — deferred (§8.2). Worth a follow-up ticket once
   `manualChunks` → `advancedChunks` can be validated against bundle sizes.
2. **React 19** — no security driver. If taken later it pulls `@types/react@19`,
   `react-diff-viewer-continued@4.x`, and a re-test of every `src/components/ui/*`.
3. **`zod@4`, `recharts@3`** — breaking majors, no advisories. Separate tickets.
4. **JWT resource server** — `SecurityConfig`'s Javadoc still describes the
   session-vs-JWT deviation from ADR-0004. Unchanged by this migration; still open.
5. **Postgres 17/18, Redis 8** — intentionally excluded so they cannot confound the
   migration's test results.
6. **Testcontainers 2 idioms** — `org.testcontainers.postgresql.PostgreSQLContainer` and
   `@EnabledIfDockerAvailable` are the new spellings. Cosmetic; deferred.
