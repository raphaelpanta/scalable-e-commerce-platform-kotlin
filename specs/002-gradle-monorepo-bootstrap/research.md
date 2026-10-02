# Research: Bootstrap Gradle Kotlin DSL Multimodule Monorepo

**Feature**: 002-gradle-monorepo-bootstrap | **Date**: 2026-10-02

Version baseline comes from specs/004-ecommerce-platform-mvp/research.md §14 and is not
re-litigated here: JDK 25 LTS, Gradle 9.8.0, Spring Boot 4.1.1 with its managed Kotlin 2.3.21,
Spring Cloud 2025.1.3, Kotest 6.2.5, Pitest 1.30.0 with `info.solidsoft.pitest` 1.19.0, Pact JVM
4.7.5, Cucumber JVM 7.34.x, Testcontainers 2.0.5, Konsist 0.17.3 (ArchUnit 1.5.1 fallback).
Versions not listed there (ktlint Gradle plugin and engine, detekt, MockK, WireMock, the Pitest
JUnit 5 plugin, Cucumber 7.34 patch level) are resolved to the latest stable release compatible with
Kotlin 2.3.21 and JDK 25 on the day the catalogue is written (task "Create the version catalogue")
and recorded in `gradle/libs.versions.toml`; this is a lookup, not an open question.

## 1. Convention plugins versus `allprojects`/`subprojects` blocks

- **Decision**: Every shared rule is a precompiled script plugin in `build-logic/src/main/kotlin`
  (`kotlin-domain`, `kotlin-application`, `kotlin-service`, `pact`, `pitest`, `docker-image`,
  `quality`, plus the internal `kotlin-base` and `repository-root`). Modules opt in with
  `plugins { id("kotlin-domain") }`. The root build script only applies `repository-root`.
- **Rationale**: A named plugin is the unit FR-005 and FR-012 talk about ("apply by name"). It is
  explicit, testable in isolation with TestKit, compatible with the configuration cache and
  project isolation, and a module that forgets it is detectable (FR-013). `allprojects` configures
  modules from the outside, applies to modules that should be exempt, and cannot be tested.
- **Alternatives considered**: `allprojects`/`subprojects` in the root script (implicit coupling,
  order-dependent, no unit tests, hostile to project isolation); Groovy init scripts (forbidden by
  Principle I); copying build snippets into each module (violates FR-005).

## 2. Included build versus `buildSrc`

- **Decision**: `build-logic/` is an included build registered through
  `pluginManagement { includeBuild("build-logic") }` in `settings.gradle.kts`, with its own
  `settings.gradle.kts`, its own `check`, and the version catalogue imported with
  `versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`.
- **Rationale**: Plugins from an included build are resolved before project configuration, edits
  to a convention only invalidate tasks that use it (not the entire build cache, as `buildSrc`
  does), and the included build has its own test task that the root `verify` calls (FR-018). The
  constitution names `build-logic/` explicitly. Precompiled script plugins cannot use the
  type-safe `libs` accessor, so they read the catalogue through
  `the<VersionCatalogsExtension>().named("libs")`; the build-logic `build.gradle.kts` uses the
  normal accessors for plugin dependencies.
- **Alternatives considered**: `buildSrc` (cache invalidation on every edit, no separate test
  lifecycle, discouraged by Gradle for multi-project builds); a published plugin repository
  (release overhead with no benefit in a monorepo).

## 3. Version-literal check implementation

- **Decision**: A custom task `checkVersionLiterals` (class `CheckVersionLiteralsTask` in
  `com.ecommerce.build`) registered on the root project by `repository-root` and wired into
  `verify`. It scans every `*.gradle.kts` file in the repository (root, modules, `build-logic`
  scripts and settings) except build output, `.gradle`, `node_modules` and
  `build-logic/src/test/resources/fixtures`, and reports `path:line` for each match of:
  1. a quoted Maven coordinate with a literal version, regex `"[\w.\-]+:[\w.\-]+:[\w.\-+]*\d[\w.\-+]*"`
     (a `$` or `libs.` reference never matches);
  2. a plugin version, regex `id\("[^"]+"\)\s+version\s+"[^"]+"` and `kotlin\("[^"]+"\)\s+version\s+"[^"]+"`;
  3. a tool version assignment, regex `[Vv]ersion\s*(=|\.set\()\s*"?[^"\s)]*\d`;
  4. a toolchain literal, regex `JavaLanguageVersion\.of\(\s*\d+` or `jvmToolchain\(\s*\d+`.
  The failure message is `<file>:<line>: version literal '<text>'; declare it in
  gradle/libs.versions.toml and reference it through the catalogue`. The task declares its input
  file tree and a marker output so it is cached and up to date when nothing changed.
- **Rationale**: Gradle has no built-in "no literal versions" switch; a text scan is simple,
  deterministic, fast, covers precompiled plugin scripts and settings (which dependency-resolution
  rules do not see), and gives the line-level message SC-003 and the edge case "points to the
  catalogue" require. A seeded-violation TestKit test proves it fails.
- **Alternatives considered**: Resolution-strategy rules that reject unmanaged versions (only see
  resolved graphs, miss plugin and tool versions); a Kotlin script parser (heavy, brittle across
  Gradle API changes); relying on code review plus Renovate (not a build failure); detekt custom
  rule (does not run on `.gradle.kts` build logic in the same way and couples two tools).

## 4. Toolchain enforcement (JDK and Gradle)

- **Decision**: Four pins plus one consistency check. `gradle/libs.versions.toml` holds
  `jdk = "25"` and `gradle = "9.8.0"` as the single declaration. The derived pins are
  `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl` for `gradle-9.8.0-bin.zip`,
  `distributionSha256Sum`, `validateDistributionUrl=true`), `gradle/gradle-daemon-jvm.properties`
  (`toolchainVersion=25`, so Gradle itself runs on JDK 25), and `.sdkmanrc` (`java=25.0.4-tem`) /
  `.java-version` (`25`) for developer version managers. `kotlin-base` sets
  `kotlin { jvmToolchain(<catalogue jdk>) }` and Java `toolchain.languageVersion`.
  `ToolchainConsistencyTask` (`checkToolchain`, first dependency of `verify`) fails with
  `Required JDK 25 (gradle/libs.versions.toml [versions] jdk) but .sdkmanrc says 24` style messages
  when any pin disagrees with the catalogue or when `GradleVersion.current()` is not 9.8.0
  (message: run `./gradlew`). `org.gradle.java.installations.auto-download=false` keeps provisioning
  explicit so an absent JDK 25 fails fast with Gradle's toolchain message.
- **Rationale**: FR-002 demands pinned and enforced with an explanatory failure. The daemon JVM
  property and toolchains make Gradle and compilation use JDK 25 regardless of the shell's
  `JAVA_HOME`; the consistency check stops the four pins drifting apart, which pure tooling cannot.
- **Alternatives considered**: Foojay toolchain resolver for auto-download (hidden network
  dependency, conflicts with the offline requirement); only `.java-version` (not enforced by
  Gradle); a `check(JavaVersion.current() == ...)` in settings (runs on whatever JDK started the
  daemon, so it fails late and says nothing about compilation).

## 5. Quiet logging configuration

- **Decision**: In `gradle.properties`: `org.gradle.console=plain`, `org.gradle.logging.level=quiet`,
  `org.gradle.warning.mode=none`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`,
  `org.gradle.parallel=true`. In `kotlin-base`, every `Test` task gets
  `testLogging { events(FAILED); exceptionFormat = FULL; showStandardStreams = false;
  lifecycleLogLevel = LogLevel.QUIET }` plus `jvmArgs("-XX:+EnableDynamicAgentLoading")` so JDK 25
  agent warnings do not reach stderr. Pitest, ktlint and detekt are configured with report-only
  output and verbose off.
- **Rationale**: Constitution Principle VIII fixes the settings. One non-obvious point:
  test-failure events are logged at the lifecycle level, which `logging.level=quiet` hides, so
  `lifecycleLogLevel = QUIET` is required for the failing assertion to appear (FR-003, SC-002); a
  TestKit test asserts both halves: a passing run prints nothing, a failing run prints the
  assertion message and no passing-test names. Plain console removes colour and progress codes for
  agents and log collectors.
- **Alternatives considered**: Passing `-q` only on the command line (not applied by IDEs, hooks
  or CI consistently); a custom test listener (more code than `testLogging`); `showStandardStreams`
  with a filter (violates the constitution).

## 6. Test source set design

- **Decision**: `kotlin-service` registers four JVM test suites through the `jvm-test-suite` plugin,
  created by `TestLayers.kt`: `test` (unit, default), `integrationTest`, `contractTest` (wired by
  `pact`) and `acceptanceTest`. Each has its own `src/<name>/kotlin` and `src/<name>/resources`,
  uses JUnit Platform, a task of the same name, and `shouldRunAfter` the previous layer;
  `check` depends on all four. Gradle 9 fails a test task that finds no tests, so every task sets
  `failOnNoDiscoveredTests = false` (edge case: an empty layer reports "no tests" and passes).
  Integration, contract and acceptance suites see the main output and the unit suite's test
  dependencies but not each other's classes. `kotlin-domain` and `kotlin-application` register
  `test` only.
- **Rationale**: Separate source sets give separate classpaths, separate reports and the
  separately runnable single-layer commands FR-014 and SC-006 require
  (`./gradlew -q :services:catalog:infrastructure:contractTest`, or `./gradlew -q contractTest` for
  the whole repository). Test suites are the supported Gradle API and the Kotlin plugin integrates
  with them.
- **Alternatives considered**: JUnit tags in one source set (one classpath, layers cannot be
  isolated, Spring and Pact dependencies leak into unit tests); separate Gradle modules per layer
  (module explosion); the legacy `sourceSets` plus hand-made `Test` tasks (works but unsupported
  by new tooling and more error-prone).

## 7. Konsist versus ArchUnit for architecture rules

- **Decision**: Konsist 0.17.3 is the rule engine. The rules are written once in
  `config/architecture/src/test/kotlin/com/ecommerce/architecture/ArchitectureRules.kt`; the
  `kotlin-service` convention adds that directory to each infrastructure module's `test` source set
  and passes `architecture.serviceRoot` and `architecture.basePackage` (`com.ecommerce.<service>`)
  as system properties, so the rules execute inside each infrastructure module (as the constitution
  requires) with no per-service copy. Rules: domain files may not import `org.springframework`,
  `io.r2dbc`, `jakarta`, `org.flywaydb`, or `<base>.application` /
  `<base>.infrastructure`; application files may not import `<base>.infrastructure` or any
  `org.springframework` type. Each violation message reads
  `Rule '<rule>' violated by <file>: import <import>`. A seeded-violation test writes an offending
  file to a temporary directory and asserts the rule fails and names that file. Gradle module
  separation (`kotlin-domain` and `kotlin-application` guards) is the first line of defence; Konsist
  is the second and also catches package-level rules.
- **Rationale**: Konsist reasons about Kotlin source (not bytecode), so it handles value classes,
  extension functions and top-level declarations naturally and needs no compiled output of the
  layers it checks; its API is Kotlin-idiomatic. The constitution allows either; research §14 of
  004 already selected Konsist with ArchUnit as the fallback.
- **Alternatives considered**: ArchUnit 1.5.1 (bytecode based, mature, better Java-centric rules,
  weaker on Kotlin-specific constructs; kept as fallback if Konsist 0.17.3 cannot parse Kotlin
  2.3, in which case only `ArchitectureRules.kt` changes); Gradle dependency-graph checks alone
  (cannot see imports inside one module); detekt custom rules (couples architecture to lint).

## 8. Pitest Kotlin plugin path

- **Decision**: The default path ships now and needs no licence: Pitest 1.30.0 through
  `info.solidsoft.pitest` 1.19.0 with the JUnit 5 plugin, `targetClasses` set to
  `<base>.domain.*` or `<base>.application.*`, `mutationThreshold` default 80, history-based
  incremental analysis (`enableDefaultIncrementalAnalysis = true`), and explicit exclusions for
  Kotlin-synthetic code: `excludedClasses` `*$WhenMappings`, `*$DefaultImpls`, `*Kt$*$1`,
  `avoidCallsTo` `kotlin.jvm.internal`, `kotlin.Intrinsics`, `kotlinx.coroutines`, and
  `excludedMethods` `toString`, `hashCode`, `equals`, `copy`, `component*`. The Arcmutate
  `pitest-kotlin-plugin` (the maintained Kotlin mutator and filter) is a non-blocking follow-up:
  a task asks for a licence for this public MIT repository and records the answer in
  `docs/build.md`; if granted, the change is one catalogue entry and one `pitestImplementation`
  line in `pitest.gradle.kts`, and the exclusions are then reviewed. The convention fails the
  build at configuration time when a module sets a threshold below
  `QualityThresholds.MINIMUM_MUTATION_THRESHOLD` (80) and skips the `pitest` task when the module
  has no unit tests (`onlyIf`, plus `failWhenNoMutations = false`).
- **Rationale**: Principle VIII requires Pitest with the Kotlin plugin "at minimum" on domain and
  application code; the open-source `pitest-kotlin` is archived and the replacement's licence terms
  are unconfirmed (004 research §14), so the build must not be hostage to a licence decision. The
  exclusion path still yields meaningful scores because domain and application code is small,
  pure and property-tested. Mutation runs are incremental locally and effectively full in CI (no
  history on a cold checkout).
- **Alternatives considered**: Block the feature until the licence answer arrives (delays every
  later feature); disable mutation testing for Kotlin until licensed (breaks the constitution);
  Stryker-style alternatives for the JVM (none maintained for Kotlin); lowering the threshold to
  compensate for synthetic-code mutants (forbidden by the floor).

## 9. Docker build through a Gradle `Exec` task versus a separate CI step

- **Decision**: `docker-image` registers `dockerImage`, an `Exec` task that runs
  `docker build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=<gradle path> -t
  <service>:<project version> .` from the repository root. The Dockerfile is the multi-stage file
  from 004 research §6 (stage 1 builds with the wrapper and a BuildKit Gradle cache mount, stage 2
  extracts Spring Boot layers, stage 3 is a non-root JRE runtime with `HEALTHCHECK` and
  `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`; base images pinned by digest when the file is
  written). The docker executable is a property (`dockerExecutable`, default `docker`) so TestKit
  can substitute a stub that records its arguments. `dockerImage` is not part of `check` or
  `verify`.
- **Rationale**: Developers and CI then run the same command
  (`./gradlew :services:catalog:infrastructure:dockerImage`), the Dockerfile stays the single
  recipe, and the task is discoverable next to the module. Keeping it out of `verify` keeps the
  gate within the 10-minute budget and avoids a mandatory image build per run; the pipelines of
  feature 004 call it in the image-publish stage.
- **Alternatives considered**: A shell step only in the workflow (not reproducible locally, hides
  the build argument contract); Jib or Spring Boot buildpacks through Gradle (rejected by the
  requester in 004 research §6); including `dockerImage` in `verify` (daemon-heavy and slow for
  every local run).

## 10. Reference service scope

- **Decision**: `services/catalog` for the bounded context "catalogue", with no business rules.
  Domain: value object `ServiceName` (kebab-case, 3 to 40 characters, validated at construction,
  returns a sealed `ServiceNameResult`) and sealed `HealthStatus` (`Up`, `Down(reason)`).
  Application: port `HealthProbe` (`suspend fun check(): HealthStatus`), `HealthReport`, use case
  `CheckServiceHealth` that evaluates all probes and aggregates reasons. Infrastructure: Spring
  Boot WebFlux application, `DatabaseHealthProbe` (R2DBC `SELECT 1`), `ReactiveHealthIndicator`
  adapter calling the use case, a `CorrelationIdWebFilter` (accepts or generates
  `X-Correlation-Id`, echoes it, mirrors it into MDC), Actuator `health` and `prometheus` on
  management port 8081, ECS structured logs, Flyway `V1__baseline.sql`, and the four test layers:
  Kotest property tests; Testcontainers integration tests (health, Flyway, metrics, correlation id,
  JSON log line); a Pact consumer test for a `platform-probe` consumer plus provider verification;
  one Cucumber feature in business language. Package root `com.ecommerce.catalog`.
- **Rationale**: Large enough to make every convention do real work (domain mutation targets,
  a port with a mock, an adapter, a database, a pact edge, an acceptance scenario) and small
  enough to carry no business logic (FR-008). Naming it `catalog` lets feature 004 grow it in
  place instead of replacing a throwaway.
- **Alternatives considered**: A hello-world with no database (does not exercise Flyway, R2DBC or
  Testcontainers, the wiring every real service needs); a throwaway `sample` service (wasted work
  and a rename later); a full catalogue domain (business logic belongs to 004).

## 11. `verify` task composition

- **Decision**: Root `verify` (group `verification`) depends on, in this order:
  `checkToolchain`, `checkVersionLiterals`, the convention guard (evaluated at the end of
  configuration), `gradle.includedBuild("build-logic").task(":check")`, the `check` task of every
  project that has one, and `frontendCheck` when `frontend/package.json` exists.
  `frontendCheck` aggregates `frontendLint` and `frontendTest`, Exec tasks running
  `npm --silent --prefix frontend run lint` and `... run test`. Domain and application `check`
  includes `pitest`; infrastructure `check` includes all four layers.
- **Rationale**: One entry point (FR-001) that the harness gate, the pull-request gate and
  developers share; `check` is the Gradle-standard aggregate so new modules join automatically;
  the included build's `check` must be called explicitly because included builds are not part of
  `check` in the root build; the frontend hook is conditional so this feature passes without a
  frontend (FR-015). `dockerImage` stays out (section 9).
- **Alternatives considered**: Making `verify` an alias of `check` only (misses the included
  build, the literal check and the frontend); a Makefile or script wrapper (second source of truth
  and not Gradle-cacheable); always registering frontend tasks (fails when no `package.json`).

## 12. Testing convention plugins with Gradle TestKit

- **Decision**: `build-logic/src/test/kotlin/com/ecommerce/build` contains one Kotest spec per
  plugin or guard. A helper `FixtureProject` copies a fixture from
  `build-logic/src/test/resources/fixtures/<name>` into a temporary directory, copies the real
  `gradle/libs.versions.toml`, `.editorconfig`, `.java-version`, `.sdkmanrc`,
  `gradle/gradle-daemon-jvm.properties` and `gradle/wrapper/gradle-wrapper.properties`, appends the
  standard resolution preamble to the fixture's `settings.gradle.kts`, and runs `GradleRunner` with
  `withPluginClasspath()`, a shared testkit directory under `build-logic/build/testkit` (so
  dependency downloads and the build cache are reused across specs) and `-q --stacktrace`.
  Seeded-violation fixtures exist for the version-literal check, ktlint, detekt, the architecture
  rules, the convention guard, the module boundary guards, the mutation threshold floor and weak
  mutation scores. Fixtures are excluded from the literal check and from ktlint of the real build.
  The docker executable is stubbed with a script on a temporary `PATH`; `npm` likewise.
- **Rationale**: TestKit is the supported way to execute a real Gradle build against a plugin
  (FR-018), exercising configuration-cache and quiet-output behaviour that unit tests of plugin
  classes cannot. Using the real catalogue keeps fixtures honest about versions.
- **Alternatives considered**: `ProjectBuilder` unit tests (do not run task graphs, no real
  logging behaviour); only testing through the reference service (misses failure paths that
  cannot be committed, such as a seeded violation); mocking Gradle APIs (tests the mock).

## Remaining open items

None blocking. Non-blocking follow-ups recorded as tasks: Arcmutate licence enquiry (section 8),
pin base-image digests in the Dockerfile and action SHAs in the workflow on the day they are
written, and a PATCH amendment of the constitution's "Spring Boot 3.x" wording (proposed in 004
research §14).
