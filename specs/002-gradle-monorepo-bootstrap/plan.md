# Implementation Plan: Bootstrap Gradle Kotlin DSL Multimodule Monorepo

**Branch**: `002-gradle-monorepo-bootstrap` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-gradle-monorepo-bootstrap/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Turn the empty repository into a reproducible Gradle Kotlin DSL monorepo that every later feature
builds on. One command, `./gradlew -q verify`, builds every module and runs every check silently
on success and noisily only on failure. The Gradle wrapper (9.8.0) and the JDK (25 LTS) are pinned
and enforced; every version lives once in `gradle/libs.versions.toml`, and a build check fails on
any module-level version literal. Shared behaviour is packaged as convention plugins in the
`build-logic/` included build (`kotlin-domain`, `kotlin-application`, `kotlin-service`, `pact`,
`pitest`, `docker-image`, `quality`, plus the internal `kotlin-base` and `repository-root`), and the
conventions themselves are tested with Gradle TestKit. A reference service, `services/catalog`
(bounded context "catalogue"), proves the constitution's layers end to end: three Gradle modules
(`domain`, `application`, `infrastructure`), four test layers (`test`, `integrationTest`,
`contractTest`, `acceptanceTest`), Konsist architecture rules, Pitest mutation testing at 80 %, a
health endpoint, structured logs and Prometheus metrics. A `newService` task scaffolds further
services in the same shape. Build documentation (`docs/build.md`), a reserved `frontend/` location
and a GitHub Actions workflow (`.github/workflows/verify.yml`, containerised self-hosted runner)
that runs the same verify command complete the feature. Feature 004 builds its services on this.

## Technical Context

**Language/Version**: Kotlin 2.3.21 (the version managed by Spring Boot 4.1.1; K2 only) on JDK 25
LTS (Temurin 25.0.4). Build tool: Gradle 9.8.0 Kotlin DSL (no Groovy anywhere), wrapper pinned with
a distribution checksum.

**Primary Dependencies**: Spring Boot 4.1.1 (Spring Framework 7) with WebFlux, Actuator and
kotlinx-coroutines (versions managed by the Boot BOM, not repeated in the catalogue); Spring Data
R2DBC + PostgreSQL R2DBC driver; Flyway over JDBC (start-up only); Micrometer Prometheus registry;
Spring structured logging (`logging.structured.format.console=ecs`); Spring Cloud 2025.1.3 pinned in
the catalogue for feature 004 (not applied here). Build plugins: Spring Boot Gradle plugin,
`org.jetbrains.kotlin.jvm` + `plugin.spring`, `org.jlleitschuh.gradle.ktlint`, detekt (release
line compatible with Kotlin 2.3.21), `info.solidsoft.pitest` 1.19.0 (Pitest 1.30.0).

**Storage**: PostgreSQL via R2DBC for the reference service; schema managed by Flyway migration
`V1__baseline.sql` (one marker table, no business data). Tests use Testcontainers 2.0.5
(`postgres:18-alpine`).

**Testing**: Kotest 6.2.5 (property testing by default for domain and application), MockK at port
edges only, Testcontainers 2.0.5, WireMock (stubs for external HTTP, available to every service),
Pact JVM 4.7.5 (consumer + provider), Cucumber JVM 7.34.x with the JUnit Platform engine, Konsist
0.17.3 for architecture rules (ArchUnit 1.5.1 held as fallback), Pitest with exclusions for
Kotlin-synthetic code (Arcmutate Kotlin plugin is a non-blocking follow-up, research §8). The
convention plugins are tested with Gradle TestKit in `build-logic/src/test`. Four source sets per
service infrastructure module: `test`, `integrationTest`, `contractTest`, `acceptanceTest`; domain
and application modules carry `test` only.

**Target Platform**: Developer machines (macOS/Linux) with JDK 25 and a Docker-API-compatible
engine (needed only by the Testcontainers-based layers and by `dockerImage`); Linux containers for
runtime images; CI on GitHub Actions with a containerised self-hosted runner that has Docker
socket access.

**Project Type**: Multi-module Gradle monorepo (build infrastructure plus one reference
microservice). Frontend location reserved only.

**Performance Goals**: `./gradlew -q verify` under 10 minutes from a fresh clone with an empty
Gradle cache and under 2 minutes with nothing changed (SC-001); passing run prints at most 5 lines
(SC-002; target is zero); a new service added and verified in under 15 minutes (SC-004); PR status
within 15 minutes of opening (SC-008; workflow `timeout-minutes: 15`).

**Constraints**: Gradle Kotlin DSL only; every version in the catalogue (zero literals); quiet
output (`org.gradle.console=plain`, `org.gradle.logging.level=quiet`, `org.gradle.warning.mode=none`,
test logging `FAILED` only with `ExceptionFormat.FULL` and no standard streams); build cache and
configuration cache on; works offline after one online build; warnings are errors (Kotlin compiler,
detekt, ktlint); mutation threshold floor 80 %; no secrets in committed configuration.

**Scale/Scope**: 1 included build, 3 modules for the reference service (+ 3 per later service),
9 convention plugins, 4 test layers, 18 functional requirements, 8 success criteria, 110 tasks.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Gate | Pre-research | Post-design |
|---|-----------|------|--------------|-------------|
| I | Kotlin-idiomatic monorepo, Gradle Kotlin DSL | Every build script is `*.gradle.kts`; versions only in `gradle/libs.versions.toml` (enforced by `checkVersionLiterals`); shared logic only in `build-logic/` conventions; ktlint + detekt applied by the `quality` plugin with warnings as errors | PASS | PASS - conventions and the literal check make duplication a build failure |
| II | Clean/Hexagonal + DDD | Reference service is three Gradle modules; `kotlin-domain` forbids any project or framework dependency, `kotlin-application` may depend only on the sibling `domain`; Konsist rules in the infrastructure module's `test` set are a second guard; value object `ServiceName` validated at construction | PASS | PASS - boundaries enforced by module graph and by Konsist, both seeded-violation tested |
| III | Security by design (OWASP Top 10 / API Top 10) | Only health and metrics are exposed (`management.endpoints.web.exposure.include=health,prometheus` on management port 8081); no business endpoints, no PII, no authentication surface yet; DB credentials come from environment variables with no committed defaults; threat model below | PASS (scoped: auth, rate limits, idempotency arrive with feature 004; dependency vulnerability scanning and SBOM are added to the 004 pipeline, research §4 of 004) | PASS |
| IV | Functional & non-blocking | Domain/application are immutable pure Kotlin with sealed results; application ports are `suspend`; WebFlux + R2DBC; Flyway is the single documented blocking exception (Complexity Tracking) | PASS | PASS |
| V | Layered test contract | `kotlin-service` creates `test`, `integrationTest`, `contractTest`, `acceptanceTest`, each separately runnable; Kotest property tests for domain/application; Testcontainers for the database; Pact consumer + provider for the health edge; Cucumber feature in business language; Pitest 80 % on domain and application; TestKit tests for the build logic itself | PASS | PASS - all four layers present and passing in the reference service |
| VI | Microservice boundaries & contracts | Reference service owns its database and exposes health, metrics and ECS JSON logs with a correlation id header; shared code limited to build logic | PASS | PASS |
| VII | TypeScript + React frontend | Not applicable: `frontend/README.md` reserves the location; `verify` runs frontend `lint` and `test` once `frontend/package.json` exists | N/A (recorded) | N/A |
| VIII | Token-efficient, hook-driven harness | `gradle.properties` carries the quiet-logging settings; test logging restricted to `FAILED`; `verify` is the end-of-task gate command used by `.claude/hooks/stop-full-check.sh`; mutation threshold floor enforced; no Groovy | PASS | PASS |

No gate failures. Deviations are listed in Complexity Tracking.

## Threat Model Summary (Principle III)

| Asset | Abuse case | Control |
|-------|-----------|---------|
| Build supply chain (plugins, dependencies) | Malicious or typosquatted dependency; version drift between modules | Single catalogue, `FAIL_ON_PROJECT_REPOS` with Maven Central and the Gradle Plugin Portal only, wrapper distribution checksum, version-literal check |
| CI runner (self-hosted, public repository) | Untrusted pull request executes code on the runner | Workflow runs only for non-fork pull requests and pushes to `main`, actions pinned by commit SHA, `permissions: contents: read`; ephemeral runner registration is a 004 platform task |
| Reference service endpoints | Information leak from actuator | Only `health` and `prometheus` exposed, on a separate management port, health details hidden (`show-details: never`) |
| Credentials | Secrets committed in configuration | Database user and password read from `CATALOG_DB_USER` / `CATALOG_DB_PASSWORD`; tests use ephemeral Testcontainers credentials |

## Project Structure

### Documentation (this feature)

```text
specs/002-gradle-monorepo-bootstrap/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── checklists/requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command)
```

There is no `data-model.md` or `contracts/` directory: the feature stores no domain data and
publishes no external API beyond the health endpoint, whose contract is the Pact in the reference
service's `contractTest` layer.

### Source Code (repository root)

```text
.sdkmanrc                            # java=25.0.4-tem
.java-version                        # 25
.editorconfig                        # ktlint_official, max_line_length = 120
.gitignore  .gitattributes  .dockerignore
gradlew  gradlew.bat
gradle.properties                    # quiet logging, caching, configuration cache
settings.gradle.kts                  # pluginManagement.includeBuild("build-logic"); includeService("catalog")
build.gradle.kts                     # plugins { id("repository-root") }
gradle/
├── libs.versions.toml               # the only place for versions
├── gradle-daemon-jvm.properties     # toolchainVersion=25
└── wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}   # Gradle 9.8.0 + sha256
config/
├── detekt/detekt.yml                # maxIssues: 0, buildUponDefaultConfig
└── architecture/src/test/kotlin/com/ecommerce/architecture/
    ├── ArchitectureRules.kt         # Konsist rules, one definition for every service
    ├── ArchitectureTest.kt          # runs the rules against <service>/{domain,application}
    └── ArchitectureRulesSeededViolationTest.kt
build-logic/                         # included build, has its own tests (FR-018)
├── settings.gradle.kts              # catalogue imported from ../gradle/libs.versions.toml
├── build.gradle.kts                 # kotlin-dsl, plugin dependencies, TestKit, ktlint, detekt
└── src/
    ├── main/
    │   ├── kotlin/
    │   │   ├── kotlin-base.gradle.kts          # internal: Kotlin JVM, toolchain, compiler flags, test logging
    │   │   ├── quality.gradle.kts              # ktlint + detekt, warnings as errors
    │   │   ├── kotlin-domain.gradle.kts        # + pitest; no project/framework dependencies
    │   │   ├── kotlin-application.gradle.kts   # + pitest; depends on sibling domain only
    │   │   ├── kotlin-service.gradle.kts       # Spring Boot app, four test layers, pact, docker-image, Konsist
    │   │   ├── pact.gradle.kts                 # contractTest layer wiring
    │   │   ├── pitest.gradle.kts               # mutation testing, floor 80
    │   │   ├── docker-image.gradle.kts         # dockerImage task (Exec, multi-stage Dockerfile)
    │   │   ├── repository-root.gradle.kts      # internal: verify, guards, frontend hook, newService
    │   │   └── com/ecommerce/build/
    │   │       ├── TestLayers.kt  QualityThresholds.kt  CheckVersionLiteralsTask.kt
    │   │       ├── ConventionGuard.kt  ToolchainConsistencyTask.kt  ScaffoldServiceTask.kt
    │   └── resources/service-template/         # scaffold source for newService
    └── test/
        ├── kotlin/com/ecommerce/build/         # TestKit tests, one class per plugin/guard
        └── resources/fixtures/                 # small Gradle projects driven by TestKit
services/
└── catalog/                         # reference service, bounded context "catalogue"
    ├── domain/                      # pure Kotlin: ServiceName, HealthStatus; src/test
    ├── application/                 # CheckServiceHealth use case, HealthProbe port; src/test
    └── infrastructure/              # Spring Boot app, health indicator, DB probe, Flyway migration,
        └── src/{main,test,integrationTest,contractTest,acceptanceTest}
platform/docker/Dockerfile           # shared multi-stage Dockerfile, parameterised by SERVICE_MODULE
docs/build.md                        # build documentation (FR-016)
README.md                            # entry point, links to docs/build.md
frontend/README.md                   # reserved location only
.github/workflows/verify.yml         # ./gradlew -q verify on pull_request and push to main
```

**Structure Decision**: One included build (`build-logic`) holds all shared Gradle logic; one
service directory per bounded context holds three Gradle modules. The layout is a strict subset
of the target in specs/004 (`services/gateway`, `libs/*`, `contracts/`, `acceptance/`,
`platform/compose|observability|ci-runner` are added by feature 004). The build never relies on
directory scanning: services are registered with `includeService("<name>")` in
`settings.gradle.kts`, and every module names its convention in its own one-line
`build.gradle.kts`.

## Requirement Traceability

| FR | Artifact |
|----|----------|
| FR-001 single command | `gradlew`, `gradle/wrapper/*`, root `verify` task in `build-logic/src/main/kotlin/repository-root.gradle.kts`, `docs/build.md` |
| FR-002 pinned and enforced versions | `gradle/wrapper/gradle-wrapper.properties`, `gradle/gradle-daemon-jvm.properties`, `.sdkmanrc`, `.java-version`, `ToolchainConsistencyTask.kt`, `kotlin-base` toolchain |
| FR-003 quiet output | `gradle.properties`, test logging in `kotlin-base.gradle.kts`, `QuietLoggingTest` |
| FR-004 single catalogue, literal check | `gradle/libs.versions.toml`, `CheckVersionLiteralsTask.kt`, `VersionLiteralCheckTest` |
| FR-005 named conventions | `build-logic/src/main/kotlin/*.gradle.kts` |
| FR-006 service convention contents | `kotlin-service.gradle.kts`, `kotlin-domain/application` (+ `pitest`), `pact`, `TestLayers.kt` |
| FR-007 style violations fail | `quality.gradle.kts`, `.editorconfig`, `config/detekt/detekt.yml`, `QualityPluginTest` |
| FR-008 reference service | `services/catalog/**` |
| FR-009 architecture rules | module graph guards in `kotlin-domain`/`kotlin-application`, `config/architecture/**` (Konsist) |
| FR-010 mutation threshold | `pitest.gradle.kts`, `QualityThresholds.kt`, `PitestPluginTest` |
| FR-011 reuse and offline | `gradle.properties` (`org.gradle.caching`, `org.gradle.configuration-cache`), `BuildCacheTest` |
| FR-012 add a service | `ScaffoldServiceTask.kt`, `service-template/`, `includeService` in `settings.gradle.kts`, `docs/build.md` |
| FR-013 convention guard | `ConventionGuard.kt`, `ConventionGuardTest` |
| FR-014 layers runnable alone | `TestLayers.kt`, task names `test`/`integrationTest`/`contractTest`/`acceptanceTest` |
| FR-015 frontend hook | `frontend/README.md`, `frontendLint`/`frontendTest` tasks in `repository-root.gradle.kts`, `FrontendHookTest` |
| FR-016 documentation | `docs/build.md`, `README.md`, `RepositoryDocsAndCiTest` |
| FR-017 CI | `.github/workflows/verify.yml`, `RepositoryDocsAndCiTest` |
| FR-018 build logic tested | `build-logic/src/test/**` (TestKit), included-build `check` wired into root `verify` |

## Spec Gaps Resolved During Planning

| Gap | Decision |
|-----|----------|
| "Only a supported runtime" (FR-001) vs Testcontainers mandated by the constitution | Supported runtime = JDK 25 plus a Docker-API-compatible engine. Unit, architecture and build-logic layers need the JDK only; integration, contract and acceptance layers need Docker. |
| SC-004 "no more than two build files" with three modules per service | Counted as existing shared build files edited: only `settings.gradle.kts` (registration). `newService` creates three one-line module scripts that name the convention. |
| FR-010 "must not decrease" | Enforced as a build-time floor of 80 and by review of any threshold override in a module script; no automated ratchet file. |
| Two modules needing different versions (edge case) | Divergence needs a literal in a module script, so the version-literal check refuses it with a pointer to the catalogue. |
| Traces and OpenTelemetry | Out of scope here; structured logs, correlation id and metrics only. OpenTelemetry arrives with feature 004. |
| Per-service Dockerfile (004 layout) | One shared parameterised `platform/docker/Dockerfile`; `docker-image` passes the module as a build argument. |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 3 Gradle modules per service | Enforces the dependency rule by construction and keeps `domain` framework-free (Principle II) | One module with Konsist only: rules become advisory; a framework import compiles until a test runs |
| `build-logic` as an included build (not `buildSrc`) | Has its own tests and `check` (FR-018); editing a convention does not invalidate the whole build cache | `buildSrc`: invalidates every task cache on any edit, cannot be tested as its own build |
| Flyway runs blocking JDBC at boot in the reference service | No mature non-blocking migration tool; runs once before the service reports ready (Principle IV exception, documented in `application.yml` and `docs/build.md`) | Hand-rolled R2DBC migrations: more code and weaker tooling; skipping migrations in the template would hide the wiring every real service needs |
| Internal plugins `kotlin-base` and `repository-root` beyond the named conventions | Compiler, toolchain and test-logging rules must exist once and be shared by domain, application and service; root-level `verify` and guards need a home | Duplicating that configuration in three conventions violates FR-005; putting it in root `build.gradle.kts` is untestable with TestKit |
| Docker engine required for three test layers | Constitution Principle V mandates Testcontainers for databases | Embedded or mocked databases would test a different system than production |
| Architecture rule sources shared from `config/architecture` and compiled into every infrastructure `test` set | Rules are defined once yet execute inside each infrastructure module as the constitution requires | A `libs/architecture-rules` module adds a published artifact and a new convention for a few hundred lines |
