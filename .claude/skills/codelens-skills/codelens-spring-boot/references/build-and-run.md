# CodeLens AI — Build & Run Reference (Maven, Spring Boot 3.5.x, Java 21)

The complete project-initialization and build path for the CodeLens backend. Load when generating the project, editing `pom.xml`, or setting up the local/Docker build. Locked choices: **Maven** (build tool), **PostgreSQL** (not MySQL), **Spring Boot 3.5.x** on **Java 21**.

## Contents
1. Locked decisions (and why)
2. Spring Initializr selections
3. Generate with one curl command
4. Full `pom.xml`
5. Build & run on Windows 11
6. Maven Docker build stage
7. Notes

## 1. Locked decisions (and why)

- **Maven, not Gradle** — the dev environment already has Maven 3.9.x + JDK 21 verified; Initializr defaults to Maven; for a single-module service the Gradle speed edge is marginal. The generated `mvnw` wrapper pins the build's Maven version regardless of the local install.
- **PostgreSQL, not MySQL** — CodeLens depends on `JSONB`, `tsvector` + GIN full-text search, an ACID multi-statement review-completion transaction, and `pgvector` (G1). MySQL does these poorly or not at all. Use the **PostgreSQL Driver**.
- **Spring Boot 3.5.x, not 3.3** — 3.3 is end-of-life and no longer listed on Initializr. 3.5.x is the newest supported 3.x line, still Spring Framework 6.2 / Spring Security 6, so every pattern in these skills applies unchanged. (Boot 4.0 is the longest-support option but moves to Spring Framework 7 / Security 7 with breaking changes — not worth the migration for this build.)

## 2. Spring Initializr selections

Project **Maven**, Language **Java**, Spring Boot **3.5.x** (highest 3.5 in the dropdown), Java **21**, Packaging **Jar**. Group `com.codelensai`, Artifact `codelensai`.

Dependencies: Spring Web · Spring Reactive Web (for `WebClient` + `Flux` streaming; app stays servlet/MVC because Spring Web is also present) · WebSocket · Spring Data JPA · Spring Security · OAuth2 Client · OAuth2 Resource Server · Spring Data Redis · Validation · Spring Boot Actuator · PostgreSQL Driver · Lombok · Testcontainers · Prometheus (Observability). Optional: Spring Boot DevTools, Docker Compose Support.

Resilience4j and pgvector are **not** on Initializr — add them to `pom.xml` manually (§4 / post-MVP).

## 3. Generate with one curl command

Windows 11 ships `curl.exe`. Use `curl.exe` explicitly (PowerShell aliases bare `curl` to `Invoke-WebRequest`):

```
curl.exe https://start.spring.io/starter.zip -o codelensai.zip ^
 -d type=maven-project -d language=java -d bootVersion=3.5.11 -d javaVersion=21 ^
 -d groupId=com.codelensai -d artifactId=codelensai -d name=codelensai -d packageName=com.codelensai ^
 -d dependencies=web,webflux,websocket,data-jpa,data-redis,security,oauth2-client,oauth2-resource-server,validation,actuator,postgresql,lombok,testcontainers,prometheus
```

If curl returns HTTP 400, it's almost always `bootVersion` — change `3.5.11` to whatever exact 3.5.x the website dropdown currently lists, since Initializr only accepts versions it's actively serving. Then unzip into e.g. `C:\dev\codelensai`.

## 4. Full `pom.xml`

This is the complete dependency set (Initializr selections + the manual Resilience4j add). Testcontainers and Micrometer versions are managed by the Spring Boot parent BOM.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.11</version>   <!-- use the latest 3.5.x patch available -->
        <relativePath/>
    </parent>

    <groupId>com.codelensai</groupId>
    <artifactId>codelensai</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>codelensai</name>
    <description>CodeLens AI — real-time collaborative code review</description>

    <properties>
        <java.version>21</java.version>
        <resilience4j.version>2.2.0</resilience4j.version>
    </properties>

    <dependencies>
        <!-- Web + reactive client for LLM streaming -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId> <!-- WebClient + Flux (app stays MVC) -->
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId> <!-- STOMP / SockJS -->
        </dependency>

        <!-- Persistence + cache/queue -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Security: GitHub OAuth2 login + JWT resource server -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>

        <!-- Validation + ops -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
            <scope>runtime</scope> <!-- /actuator/prometheus for Grafana -->
        </dependency>

        <!-- PostgreSQL driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Resilience4j (NOT managed by the Boot BOM — pin explicitly) -->
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>

        <!-- Lombok (compile-time only) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Tests: JUnit 5, Mockito, Security test, Testcontainers -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope> <!-- version from the Boot parent BOM -->
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope> <!-- version from the Boot parent BOM -->
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

Enable virtual threads in `application.yml`: `spring.threads.virtual.enabled: true` (see `codelens-java21`).

For G1 later, add pgvector support: the `vector` Postgres extension plus a Java integration (`com.pgvector:pgvector` or native queries) — not a day-one dependency.

## 5. Build & run on Windows 11

From the project root (use the generated wrapper; on Windows it's `mvnw.cmd`):

```
cd C:\dev\codelensai
mvnw.cmd clean package          REM compile, run tests, build the jar
mvnw.cmd spring-boot:run        REM run locally
mvnw.cmd clean package -DskipTests   REM skip tests for a fast packaging run
```

The wrapper downloads and uses its own pinned Maven, so the locally installed Maven 3.9.x version is irrelevant to the build. (On macOS/Linux the same commands are `./mvnw …`.)

## 6. Maven Docker build stage

Multi-stage Dockerfile build stage (replaces the Gradle `bootJar` form from the master plan):

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## 7. Notes

- With both `spring-boot-starter-web` and `spring-boot-starter-webflux` present, Spring Boot runs as a **servlet (MVC) app on Tomcat** — exactly what's wanted for REST + WebSocket. WebFlux just supplies `WebClient` and `Flux` for the LLM streaming adapter; don't make the whole app reactive.
- The LLM provider needs no starter — call it through `WebClient` behind the sealed `LlmReviewProvider` adapter, or add a vendor SDK later.
- Keep secrets (GitHub app id/secret, LLM API key, DB creds) in environment variables, never in `application.yml` committed to the repo.
