# Build guide

The repository is one Gradle Kotlin DSL multimodule build. One command, `./gradlew -q verify`, builds
every module and runs every check; it prints nothing on success and only the failure on error.
Everything below describes what exists in the repository today. All commands run from the repository root.

## Layout

```text
.sdkmanrc                       # java=25.0.4-tem
.java-version                   # 25
gradle.properties               # quiet logging, build cache, configuration cache, parallel
settings.gradle.kts             # includeBuild("build-logic"), includeService("<name>") registry
build.gradle.kts                # plugins { id("repository-root") } and monorepo { exempt(...) }
gradle/
  libs.versions.toml            # the only place a version may appear
  gradle-daemon-jvm.properties  # toolchainVersion=25 (Gradle itself runs on JDK 25)
  wrapper/                      # Gradle 9.8.0 with pinned checksum
config/
  detekt/detekt.yml             # detekt rules on top of the defaults, zero findings allowed
  architecture/                 # Konsist rules, written once and run inside every service
  conformance/                  # OpenApiContract, compiled into every service's integrationTest
build-logic/                    # included build with the convention plugins and their TestKit tests
  src/main/kotlin/*.gradle.kts  # kotlin-domain, kotlin-application, kotlin-service, kotlin-boot-app,
                                #   kotlin-library, quality, pitest, pact, docker-image, plus the internal
                                #   kotlin-base and repository-root
  src/main/kotlin/com/ecommerce/build/   # task classes and helpers used by the conventions
  src/main/resources/service-template/  # source of ./gradlew newService -Pname=<context>
services/
  catalog/                      # reference service (bounded context "catalogue")
    domain/                     # pure Kotlin, unit tests only        -> id("kotlin-domain")
    application/                # use cases and ports, unit tests only -> id("kotlin-application")
    infrastructure/             # Spring Boot WebFlux app, four test layers -> id("kotlin-service")
platform/docker/Dockerfile      # shared multi-stage Dockerfile used by dockerImage
frontend/                       # the web storefront npm package (see "Verify command" and frontend/README.md)
docs/                           # this guide, ci-cd.md, harness.md
.github/workflows/verify.yml    # runs ./gradlew -q verify on pushes to main and, via pr-gate.yml, on pull requests
```

Each module has a one-line `build.gradle.kts` that names its convention, for example
`plugins { id("kotlin-domain") }`. Nothing is discovered by directory scanning: services are registered
in `settings.gradle.kts` with the `includeService("<name>")` helper, which includes the three modules
`:services:<name>:domain`, `:services:<name>:application` and `:services:<name>:infrastructure`.

A leaf module that applies none of the named conventions fails the build, unless it is exempted with a
mandatory reason in the root `build.gradle.kts`:

```kotlin
monorepo {
    exempt(":path:to:module", "reason it may skip the conventions")
}
```

## Convention plugins

| Convention | For | What it sets up |
| --- | --- | --- |
| `kotlin-domain` | `:services:<ctx>:domain` | pure Kotlin, unit tests, `pitest`; no project or framework dependency |
| `kotlin-application` | `:services:<ctx>:application` | its sibling `domain`, coroutines, MockK, `pitest`; no Spring |
| `kotlin-service` | `:services:<ctx>:infrastructure` | `kotlin-boot-app` plus the sibling `domain` and `application`, R2DBC, Flyway, PostgreSQL, Testcontainers and the Konsist rules of `config/architecture`; the module must be named `infrastructure` |
| `kotlin-boot-app` | a standalone Spring Boot WebFlux application, `:services:gateway` | Boot and Kotlin Spring plugins, Boot BOM, WebFlux, actuator, coroutines, Jackson Kotlin, Prometheus, context propagation, `springBoot { buildInfo() }`; layers `test`, `integrationTest`, `contractTest` and `contractVerify` (`pact`), `acceptanceTest` (Cucumber); WebTestClient, WireMock, MockK, Konsist; `dockerImage` |
| `kotlin-library` | `:libs:platform-core`, `:libs:platform-messaging`, `:acceptance` | Kotlin Spring plugin, the Boot BOM as a platform (Spring, Jackson and Kafka artifacts need no version), `java-test-fixtures` (`src/testFixtures/kotlin`, consumed with `testImplementation(testFixtures(project(":libs:platform-core")))`), layers `test` and `integrationTest` with MockK, WebTestClient, Spring Boot Testcontainers, Testcontainers PostgreSQL/JUnit/R2DBC and WireMock; no `pitest` unless the module applies it |

`dockerImage` names the image after the service: the parent directory of an `infrastructure` module
(`:services:catalog:infrastructure` builds `catalog:<version>`), otherwise the module itself (`:services:gateway`
builds `gateway:<version>`); the `SERVICE_MODULE` build argument is always the module path. A shared library
that is mutation tested applies `pitest` next to its convention and names its package (see "Mutation testing"):

```kotlin
plugins {
    id("kotlin-library")
    id("pitest")
}

mutation {
    targetPackage.set("com.ecommerce.platform")
}
```

## Verify command

```bash
./gradlew -q verify
```

Requirements: JDK 25 (`sdk env` reads `.sdkmanrc`; any manager that honours `.java-version` works) and,
for the integration, contract and acceptance layers and for `dockerImage`, a Docker-API-compatible
engine. Nothing else is installed: `./gradlew` downloads Gradle 9.8.0 and verifies its checksum, and the
JDK 25 toolchain is auto-provisioned when it is not installed.

Expected result: exit code 0 and no output. `verify` runs, in order of dependency:

1. `checkToolchain`: the JDK pins in `.java-version`, `.sdkmanrc` and `gradle/gradle-daemon-jvm.properties`
   must name the `jdk` version of `gradle/libs.versions.toml` (25), the wrapper must download the catalogue
   `gradle` version (9.8.0) and the build must be running on it.
2. `checkVersionLiterals`: no `*.gradle.kts` file may contain a version literal.
3. The `build-logic` included build's `check` (its TestKit tests).
4. `check` of every module: ktlint, detekt, the test layers, architecture rules and Pitest.
5. The frontend `lint` and `test` npm scripts (`frontendCheck`), once `frontend/package.json` exists. The npm
   executable defaults to `npm` and can be overridden with `-PnpmExecutable=/path/to/npm`.

The storefront package (`frontend/`, Node 24, dependencies pinned in `package-lock.json`) needs `npm install`
once per clone; `frontendLint` runs Prettier, ESLint (`--max-warnings 0`), `tsc --noEmit` and the freshness check
of the generated API types, `frontendTest` the Vitest suite. Both scripts generate the ignored
`frontend/src/api/generated/` directory when it is missing, so no manual step precedes `verify`. When `npm` is not
on the Gradle daemon's PATH (a version manager such as nvm or fnm that only your interactive shell initialises),
point the build at it: `./gradlew -q verify -PnpmExecutable=$(command -v npm)`. `./gradlew -q frontendCheck` runs
just the two frontend tasks.

Offline: after one online run, `./gradlew -q --offline verify` works from the local caches. With the build
cache and configuration cache enabled (`gradle.properties`), an unchanged re-run finishes in well under two
minutes.

## Running a test layer

The infrastructure module of a service has four layers, each its own source set; the contract layer has two
tasks (see "Contract tests"). Domain and application modules have the unit layer (`test`) only; shared libraries
have `test` and `integrationTest`.

```bash
./gradlew -q :services:catalog:infrastructure:test              # unit, architecture rules (JDK only)
./gradlew -q :services:catalog:infrastructure:integrationTest   # Testcontainers PostgreSQL (needs Docker)
./gradlew -q :services:catalog:infrastructure:contractTest      # Pact consumers: write build/pacts
./gradlew -q :services:catalog:infrastructure:contractVerify    # Pact providers: verify build/pacts (needs Docker)
./gradlew -q :services:catalog:infrastructure:acceptanceTest    # Cucumber features (needs Docker)
```

Layers run in that order when several are requested. The same task name without a module path runs that
layer in every module of the repository:

```bash
./gradlew -q integrationTest
./gradlew -q contractTest contractVerify   # every pact of the repository, written and verified
```

### OpenAPI conformance (integration layer)

Each service's `<Ctx>ContractConformanceIT` exercises every operation of `contracts/openapi/<ctx>.yaml` with its
success and documented error statuses and routes each exchange through `OpenApiContract.check`
(`config/conformance`, added to the `integrationTest` source set by `kotlin-service`; the validator
`com.atlassian.oai:openapi-request-validator-core` is an `integrationTest` dependency only, it is built on Jackson 2).
A response must match the documented status, media type, headers and schema of its operation; a request the service
accepted (2xx) must match the contract, a refused one (4xx) may break it on purpose. Objects accept members their
schema does not list unless it says `additionalProperties: false` (JSON Schema semantics). `verify` then fails for
every violation and for every documented (`operationId`, status) pair no exchange produced, unless the test defers
it with the reason (`"* 429"`: the gateway's rate limiting; `503`: an unavailable database). So a new operation or
error status in a contract fails the build until it is exercised or explicitly deferred. The file is resolved from
the module directory (`../../../contracts/openapi/<ctx>.yaml`).

## Contract tests

Pact JVM consumer and provider tests share one source set, `src/contractTest/kotlin`, and two tasks of the `pact`
convention (applied by `kotlin-boot-app`, hence by `kotlin-service`):

The storefront is a JavaScript consumer: `npm --prefix frontend run pact` writes its pacts
(`build/pacts/storefront-<provider>.json`, Pact JS) into the same root folder, so run it before `contractVerify`
whenever the storefront's edges changed (`npm --prefix frontend run pact && ./gradlew -q contractVerify`). The
providers verify it like every other pact; a missing storefront pact is ignored (`@IgnoreNoPactsToVerify`).

1. `contractTest` runs every test except those tagged `provider`: the consumer tests. They write their pact files
   (`<consumer>-<provider>.json`) to the repository root `build/pacts`, one folder shared by every module (system
   property `pact.rootDir`, `pact.writer.overwrite=true`).
2. `contractVerify` runs only the JUnit Jupiter classes tagged `provider`, after every `contractTest` task of the
   build (`mustRunAfter`), and reads the same folder (system property `pact.folder`). `check`, and so `verify`,
   depends on it; its inputs include the pact files, so a changed pact re-runs the verification.

A provider verification class carries these annotations (Pact expands `${pact.folder}` from the system property;
in Kotlin the `$` is escaped):

```kotlin
@Tag("provider")
@Provider("catalog")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
class HealthProviderVerificationTest { /* @BeforeEach and @TestTemplate take a nullable PactVerificationContext */ }
```

`@IgnoreNoPactsToVerify` keeps a provider green while no consumer has written a pact for it; the context is then
`null`, so the template uses `context?.verifyInteraction()`. Consumer tests need no ordering annotation. Because
the pact files live outside the task outputs, `contractTest` is never taken from the build cache and is re-run when
`build/pacts` is missing.

Pact broker (optional, environment variables read when the test JVM starts):

| Variable | Effect on both contract tasks |
| --- | --- |
| `PACT_BROKER_URL` | `-Dpactbroker.url`; enables everything below; `contractVerify` is then never up to date or cached |
| `PACT_BROKER_TOKEN` | `-Dpactbroker.auth.token` |
| `PACT_BROKER_USERNAME`, `PACT_BROKER_PASSWORD` | `-Dpactbroker.auth.username`, `-Dpactbroker.auth.password` |
| `GITHUB_SHA` (else `git rev-parse HEAD`) | `-Dpact.provider.version`, only with a broker URL |
| `PACT_PUBLISH_RESULTS=true` | `-Dpact.verifier.publishResults=true`, only with a broker URL; otherwise always `false` |
| `PACT_PROVIDER_BRANCH` | `-Dpact.provider.branch` (branch of the published results) and `-Dpactbroker.providerBranch` (`matchingBranch` selector), only with a broker URL |
| `PACT_URL`, `PACT_CONSUMER` | `-Dpact.filter.pacturl`, `-Dpact.filter.consumers`, only with a broker URL: a run a broker webhook dispatched verifies only that pact (classes with `@AllowOverridePactUrl`) |

Credentials are not task inputs, so they never reach a cache key. Pacts loaded with `@PactFolder` are never
published. Every service verifies broker pacts too: its provider states, message producers and `@TestTemplate` live in
an abstract `<Ctx>ProviderStates` (Spring test context, field injection), extended by `<Ctx>ProviderVerificationTest`
(`@PactFolder`, `@IgnoreNoPactsToVerify`) and `<Ctx>BrokerVerificationTest`:

```kotlin
@Tag("provider")
@Provider("catalog")
@PactBroker                       // pactbroker.url and the credentials, as above
@AllowOverridePactUrl             // PACT_URL / PACT_CONSUMER of a webhook-dispatched run
@IgnoreNoPactsToVerify
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class CatalogBrokerVerificationTest : CatalogProviderStates() {
    companion object {
        @JvmStatic
        @PactBrokerConsumerVersionSelectors
        fun consumerVersionSelectors(): SelectorBuilder = SelectorBuilder().mainBranch().deployedOrReleased() // + matchingBranch()
    }
}
```

Without `PACT_BROKER_URL` the broker classes are skipped (JUnit reports them as disabled); `newService` generates the
same three classes.

Unit tests of a single module, for example the application module of the catalogue:

```bash
./gradlew -q :services:catalog:application:test
```

## Adding a module

A new service (bounded context) is generated from the layout of the reference service `services/catalog`:

```bash
./gradlew newService -Pname=orders
./gradlew -q verify
```

`newService` validates the name against `[a-z][a-z0-9]*`, refuses a service that already exists
(`services/<name>` or its registration line) and then:

- creates `services/orders/{domain,application,infrastructure}`, each with a one-line build file naming its
  convention:

| File | Content |
| --- | --- |
| `services/orders/domain/build.gradle.kts` | `plugins { id("kotlin-domain") }` |
| `services/orders/application/build.gradle.kts` | `plugins { id("kotlin-application") }` |
| `services/orders/infrastructure/build.gradle.kts` | `plugins { id("kotlin-service") }` |

- writes the package `com.ecommerce.orders`: placeholders `BoundedContext` (domain) and `DescribeService`
  (application) with a unit test each, `OrdersApplication.kt`, `application.yml` (R2DBC and Flyway from
  `ORDERS_DB_HOST`, `ORDERS_DB_USER`, `ORDERS_DB_PASSWORD`, management port 8081, health and Prometheus
  only, the R2DBC health indicator off, `spring.reactor.context-propagation: auto`) and
  `db/migration/V1__baseline.sql`;
- adds one test per infrastructure layer, each with its own `PostgresContainerConfig` (Testcontainers
  PostgreSQL through `@ServiceConnection`) and a `config/application.yml` that keeps the context shutdown
  quiet: an integration test (health and Flyway baseline), a Pact consumer and provider pair for
  `GET /actuator/health`, and a Cucumber feature run through `@CucumberContextConfiguration`;
- appends `includeService("orders")` below the `includeService registry` comment of `settings.gradle.kts`.

The generated service passes `./gradlew -q verify` as it is (SC-004); the only existing file that changes is
`settings.gradle.kts`. The templates live in `build-logic/src/main/resources/service-template/` (placeholders
`__name__`, `__Name__`, `__NAME__`, listed in `index.txt`); `ServiceLayoutParityTest` checks that every directory
of their layout exists in `services/catalog` (which grew its own packages in feature 004). To discard a generated service, delete `services/<name>` and its
`includeService` line.

Manual equivalent: register the service with `includeService("<name>")` below the comment and create the three
modules with the one-line build files above. Source sets follow Gradle conventions: `src/main/kotlin` and
`src/test/kotlin`, plus `src/integrationTest`, `src/contractTest` and `src/acceptanceTest` in the
infrastructure module. The infrastructure module must be named `infrastructure` and sit directly under the
service directory; the base package of the architecture rules is `com.ecommerce.<service>`. A module whose
build file applies no convention fails with `Module :services:<name>:<module> applies no convention`.

## Adding a dependency

1. Add the library to `gradle/libs.versions.toml`: a version under `[versions]` (or no version when the
   Spring Boot BOM manages it) and an entry under `[libraries]`.
2. Reference it from the module's `build.gradle.kts` through the catalogue accessor, with a dash in the
   alias becoming a dot:

```kotlin
dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
```

3. Run only that module's unit tests, for example:

```bash
./gradlew -q :services:catalog:application:test
```

A literal such as `implementation("org.example:lib:1.2.3")`, a `version "..."` on a plugin id, a
`...Version = "..."` assignment or a numeric `jvmToolchain(...)` in any `*.gradle.kts` file fails
`./gradlew -q checkVersionLiterals` with the file, the line and a pointer to the catalogue. The domain and
application conventions also refuse forbidden dependencies (`domain` may not depend on any project or on
Spring, R2DBC, Flyway or Jakarta; `application` may depend only on its sibling `domain`).

## Quality gates

| Gate | When | Command |
| --- | --- | --- |
| Per-file hook gate | after every file edit by an agent | `./gradlew -q :<module>:ktlintCheck :<module>:detekt :<module>:test` |
| End-of-task gate | when an agent finishes a task | `./gradlew -q verify` |
| Pull-request gate | every pull request (`.github/workflows/pr-gate.yml` calling `verify.yml`, check name `pr-gate`) | `./gradlew -q verify` plus the mutation scripts ([harness.md](harness.md)) |
| Image build (outside `verify`) | on demand and in later delivery pipelines | `./gradlew :services:catalog:infrastructure:dockerImage` |

Warnings are errors: the Kotlin compiler, ktlint (`.editorconfig`, `ktlint_official`) and detekt
(`config/detekt/detekt.yml`, zero findings) all fail the build. `dockerImage` builds the service image from
`platform/docker/Dockerfile` with the module path as build argument; it needs Docker and is deliberately
not part of `check` or `verify`. The details of the hook gates are in [harness.md](harness.md), the
workflow in [ci-cd.md](ci-cd.md). ktlint findings are echoed at the quiet log level by a finalizer of each
`ktlint<SourceSet>SourceSetCheck` task, and a printed report is deleted, so a later passing run prints nothing.

Required harness change (recorded here instead of editing feature 001): both hooks locate `./gradlew` at the
repository root, and the per-file hook runs the command in the table. The end-of-task hook
(`.claude/hooks/stop-full-check.sh`) still runs `./gradlew -q check` plus `<module>:pitest
-Pharness.mutation.classes=<globs>`; it should run `./gradlew -q verify`, because root `check` skips
`checkToolchain`, `checkVersionLiterals` and the `build-logic` TestKit suite.

## Mutation testing

Domain and application modules apply the `pitest` convention (shared libraries may apply it too), and `pitest`
is part of their `check`.

- Threshold: the mutation score must be at least 80 %; the floor is `QualityThresholds` in `build-logic`
  and a module that sets a lower `mutationThreshold` fails at configuration time.
- Target classes, the first that applies:
  1. `-Pharness.mutation.classes=<glob>[,<glob>...]` (the hooks pass the classes of the changed files);
  2. `mutation { targetPackage.set("com.ecommerce.platform") }` in the module's build script, which mutates
     `com.ecommerce.platform.*`; a module outside `services/` must set it, or it fails at configuration with
     `Module <path> applies pitest outside services/`;
  3. for `:services:<service>:<layer>`, `com.ecommerce.<service>.<layer>.*`.
- Tests run against the mutants: every unit test of the module by default. A module whose unit layer also holds
  slow Spring tests that cannot kill mutants in the target package may narrow them in its build script;
  `:libs:platform-core` mutates only its framework-free `com.ecommerce.platform.core.*` and runs only the property
  specs of that package (`pitest { targetTests.set(setOf("com.ecommerce.platform.core.*")) }`).
- Inline functions are not covered (their bodies run inlined at the call sites), so code meant to be mutation tested
  is written as regular functions; the getters of `@JvmInline value class` properties stay uncovered for the same
  reason.
- Exclusions for Kotlin-synthetic code: `*$WhenMappings`, `*$DefaultImpls`, `*Kt$*$1`; calls to
  `kotlin.jvm.internal`, `kotlin.Intrinsics` and `kotlinx.coroutines` are not mutated; the methods
  `toString`, `hashCode`, `equals`, `copy` and `component*` are skipped.
- Modules without unit tests skip the task. Every run is a full analysis because incremental analysis
  needs the Arcmutate history plugin.
- Output is silent on success; stderr is kept in `build/pitest/stderr.log` and the report in
  `build/reports/pitest`. On failure the message names the score and the threshold.
- Follow-up: the Arcmutate Kotlin plugin (fewer false survivors on Kotlin bytecode, incremental history)
  is not licensed yet; once it is, it is one catalogue entry and one `pitest` dependency in
  `pitest.gradle.kts`.

## Blocking exception

The platform is non-blocking (WebFlux, coroutines, R2DBC). The single documented exception is Flyway in
the reference service: it migrates over JDBC once at start-up, before the service reports ready, and every
request path stays non-blocking. The exception is commented in
`services/catalog/infrastructure/src/main/resources/application.yml`; any further blocking call needs its own
documented justification.

The second documented exception is the Kafka consumer thread of `libs/platform-messaging`.
`EventListenerSupport.dispatch` runs the suspending, idempotent handler and waits for it on the listener
container's own consumer thread, never on a Netty or Reactor event loop. A `suspend` `@KafkaListener` would hand
over the next record before the previous one finished, and the events contract promises per-key ordering. The
producer side has no such exception: the outbox relay runs on a coroutine scope started by a `SmartLifecycle` bean,
reads and updates rows over R2DBC and calls `KafkaProducer.send`, which can block while it fetches metadata, on
`Dispatchers.IO`.

`dispatch` also binds the envelope's `correlationId` around the handler (T145): it is put in the MDC of the consumer
thread and in the Reactor context of the handler's coroutine (`CorrelationIds.bind`), and the coroutine runs with
`ReactorThreadLocals` of platform-core, which restores that context into the thread locals every time the coroutine
resumes (after the `processed_event` insert, on another dispatcher, ...). Consumer-side log lines therefore carry
`correlationId`, `CorrelationIds.current()` returns it, and internal calls and outbox events made by the handler copy
it. An id that is not 1 to 64 characters of `[A-Za-z0-9-]` is replaced by a UUID for the whole handler. Covered by
`EventListenerSupportTest` (unit) and `IdempotentConsumerIntegrationTest` (real Kafka and R2DBC).

Trace context (T144): internal clients are built by `WebClientDefaults.internalClient` on Boot's auto-configured
`WebClient.Builder` (platform-core exposes `spring-boot-starter-webclient`), and `awaitBodyOrProblem` /
`awaitOptionalBodyOrProblem` run the exchange inside `withReactorThreadLocals`, so the client observation is a child
of the request's observation even after the suspending handler resumed on another thread and the outbound request
carries the inbound trace in `traceparent` (`TracePropagationTest` in platform-core). Boot installs the W3C propagator
only while tracing export is enabled (`management.tracing.export.enabled`, on by default); tests that assert
propagation enable it and switch the OTLP exporter off with `management.tracing.export.otlp.enabled=false`.

Integration tests that load `platform-messaging` (outbox relay, listeners) keep the context shutdown quiet with
`logging.level.com.ecommerce.platform.messaging.PeriodicJob: error` and `org.apache.kafka: error` in their test
configuration. Without Ryuk (Podman), Testcontainers stops the containers in a JVM hook that runs concurrently with
Spring's, so the relay can still poll a database that is already gone. The library's own test configuration is
`libs/platform-messaging/src/integrationTest/resources/application.yml`.

## Measured timings

Measured on 2026-10-02 (Apple Silicon, macOS, Podman engine with 4 CPUs and 8 GB, `postgres:18-alpine` image
already pulled) from a copy of the repository without `build/` or `.gradle/` directories and an empty Gradle
user home (`GRADLE_USER_HOME` pointing to a new directory, so Gradle, the JDK 25 toolchain and every
dependency were downloaded):

| Run | Time | Output lines | Target |
| --- | --- | --- | --- |
| `./gradlew -q verify`, cold | 5 min 30 s | 0 | under 10 min (SC-001), at most 5 lines (SC-002) |
| `./gradlew -q verify`, unchanged re-run | 16 s | 0 | under 2 min (SC-001) |
| `./gradlew -q --offline verify` | 15 s | 0 | succeeds offline (FR-011) |

The catalogue image (`./gradlew :services:catalog:infrastructure:dockerImage`) is 433 MB and runs as
`uid=10001(app)`; it is tagged `catalog:unspecified` because the build sets no project version yet. The image
build runs Gradle inside the engine (3 GB daemon heap plus the Kotlin daemon), so the engine needs about 6 GB
free; with less, the build fails with "Gradle build daemon disappeared unexpectedly".

## Troubleshooting

- **JDK mismatch.** `checkToolchain` fails with lines such as
  `Required JDK 25 (gradle/libs.versions.toml [versions] jdk) but .java-version says 24` or
  `Required Gradle 9.8.0 (run ./gradlew) but this build runs on Gradle <other>`. Fix the named file so it
  agrees with the catalogue, or start the build through `./gradlew`, not a system Gradle. Run `sdk env` to
  select the JDK from `.sdkmanrc`.
- **Docker not running.** The integration, contract and acceptance layers and `dockerImage` fail with a
  Testcontainers or Docker client error such as "Could not find a valid Docker environment". Start the
  engine (Docker Desktop, Colima, Podman with the Docker socket) and re-run. The unit layers, architecture
  rules and `build-logic` tests need no Docker.
- **A convention guard failure** (`Module :x applies no convention`): give the module a build file that
  applies a convention, or exempt it in the root `build.gradle.kts` with a reason.
- **`There were N compiler errors found during analysis`** from a `detekt<SourceSet>` task breaks the silent
  output. Detekt's type resolution does not apply the Kotlin Spring (all-open) plugin, so a test base class that
  only Spring's annotations open (for example a `@SpringBootTest` class other tests extend) must be declared
  `open` explicitly. Add `detekt { debug.set(true) }` to the module temporarily to see the errors.

## Follow-ups

Non-blocking items left open by the bootstrap feature:

- **Arcmutate `pitest-kotlin-plugin` licence.** The Kotlin plugin needs an Arcmutate licence; the enquiry for
  this public MIT repository has no recorded answer yet (outcome: open), so the build uses plain Pitest with
  the Kotlin exclusions above. Once a licence exists, the switch is one catalogue entry in
  `gradle/libs.versions.toml` plus one line in `build-logic/src/main/kotlin/pitest.gradle.kts`:
  `dependencies { "pitest"(catalog.findLibrary("arcmutate-kotlin").get()) }` (and the licence file Arcmutate
  ships, outside version control).
- **Constitution wording.** `.specify/memory/constitution.md` still says "Spring Boot 3.x"; the build uses the
  current GA major (4.1.1). Amend it to "current GA major" as a PATCH through `/speckit-constitution`.
- **Refresh cadence for pinned digests and SHAs.** The base images in `platform/docker/Dockerfile`
  (`eclipse-temurin:25-jdk` and `25-jre`, pinned by digest) and the `uses:` SHAs in
  `.github/workflows/verify.yml` do not move on their own. Refresh both once a month and whenever a security
  advisory names them: resolve the new digest (`docker buildx imagetools inspect eclipse-temurin:25-jre`) or
  commit SHA (`gh api repos/<owner>/<repo>/commits/<tag> --jq .sha`), update the value and its comment
  together, and run `./gradlew -q verify` plus `./gradlew :services:catalog:infrastructure:dockerImage`.
- **Gradle SBOM (CycloneDX), deferred (T167).** The SBOM requirement is met by the CycloneDX JSON that `syft`
  writes for every built image (`.github/workflows/service-ci.yml`, job `image`, artifact `sbom-<service>`; see
  `docs/ci-cd.md`, "SBOM"). A build-time SBOM, if wanted later, is the CycloneDX Gradle plugin
  (`org.cyclonedx.bom`) applied from a `build-logic` convention plugin to the deployable modules, with its version in
  `gradle/libs.versions.toml` (the only place for versions) and its `cyclonedxBom` output published beside the syft
  artifact.
