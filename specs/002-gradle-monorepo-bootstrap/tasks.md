---
description: "Task list for feature 002: Bootstrap Gradle Kotlin DSL Multimodule Monorepo"
---

# Tasks: Bootstrap Gradle Kotlin DSL Multimodule Monorepo

**Input**: Design documents from `/specs/002-gradle-monorepo-bootstrap/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, quickstart.md

**Tests**: MANDATORY for this feature (constitution Principle V). Every convention plugin and guard
has a Gradle TestKit test in `build-logic/src/test/kotlin/com/ecommerce/build/` driven by a fixture
project in `build-logic/src/test/resources/fixtures/`; the version-literal check and the Konsist
rules have seeded-violation tests; the reference service has all four test layers. Tests are
written first and must fail before the implementation they cover.

**Organization**: Tasks are grouped by user story so each story can be implemented and tested
independently once the Foundational phase is done.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story the task belongs to (US1..US5); only used inside story phases
- Every description names the exact file path(s) it creates or edits

## Path Conventions

All paths are relative to the repository root. Shorthand used in this file and expanded in every
task: build-logic main scripts live in `build-logic/src/main/kotlin/`, build-logic helper classes in
`build-logic/src/main/kotlin/com/ecommerce/build/` (Kotlin package `com.ecommerce.build`),
TestKit tests in `build-logic/src/test/kotlin/com/ecommerce/build/`, fixtures in
`build-logic/src/test/resources/fixtures/<name>/`. Reference service package root is
`com.ecommerce.catalog`, directories `services/catalog/{domain,application,infrastructure}`.
Values to pin: JDK `25` (Temurin `25.0.4`), Gradle `9.8.0`, Kotlin `2.3.21`, Spring Boot `4.1.1`,
Spring Cloud `2025.1.3`, Kotest `6.2.5`, Pitest `1.30.0`, `info.solidsoft.pitest` `1.19.0`,
Pact JVM `4.7.5`, Cucumber JVM `7.34.x`, Testcontainers `2.0.5`, Konsist `0.17.3`, ArchUnit `1.5.1`.
Mutation threshold default and floor: `80`. Every fixture build in TestKit tests runs with `-q`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Repository-level pins, wrapper, catalogue and the empty build-logic included build

- [X] T001 [P] Create `.sdkmanrc` containing the single line `java=25.0.4-tem` and `.java-version` containing the single line `25` at the repository root (JDK 25 LTS pins for developer version managers, FR-002)
- [X] T002 [P] Create `.gitignore` (entries `.gradle/`, `**/build/`, `.kotlin/`, `.idea/`, `*.iml`, `node_modules/`, `.DS_Store`, `!gradle/wrapper/gradle-wrapper.jar`) and `.gitattributes` (`* text=auto eol=lf`, `*.bat text eol=crlf`, `gradle/wrapper/gradle-wrapper.jar binary`) at the repository root
- [X] T003 [P] Create `.editorconfig` with `root = true` and a `[*.{kt,kts}]` section setting `ktlint_code_style = ktlint_official`, `max_line_length = 120`, `indent_size = 4`, `insert_final_newline = true`
- [X] T004 Create `gradle/libs.versions.toml` with `[versions]` gradle=`9.8.0`, jdk=`25`, kotlin=`2.3.21`, spring-boot=`4.1.1`, spring-cloud=`2025.1.3`, kotest=`6.2.5`, pitest=`1.30.0`, pitest-gradle-plugin=`1.19.0`, pact=`4.7.5`, cucumber=`7.34.x` (latest published 7.34 patch), testcontainers=`2.0.5`, konsist=`0.17.3`, archunit=`1.5.1` plus latest stable releases compatible with Kotlin 2.3.21 and JDK 25 for ktlint-gradle-plugin, ktlint, detekt, mockk, wiremock, pitest-junit5-plugin; `[libraries]` for the Kotlin, Spring Boot, ktlint, detekt and Pitest Gradle plugins (used by build-logic), Kotest (`kotest-runner-junit5`, `kotest-assertions-core`, `kotest-property`), mockk, Testcontainers (`testcontainers-postgresql`, `testcontainers-junit-jupiter`), Pact (`pact-consumer-junit5`, `pact-provider-junit5`), Cucumber (`cucumber-java`, `cucumber-junit-platform-engine`), `junit-platform-suite`, `junit-platform-launcher`, konsist, wiremock, and Boot-managed libraries without versions (`spring-boot-starter-webflux`, `spring-boot-starter-actuator`, `spring-boot-starter-data-r2dbc`, `spring-boot-starter-flyway`, `spring-boot-starter-webflux-test`, `spring-boot-testcontainers`, `kotlinx-coroutines-core`, `kotlinx-coroutines-reactor`, `jackson-module-kotlin`, `micrometer-registry-prometheus`, `context-propagation`, `postgresql`, `r2dbc-postgresql`, `flyway-database-postgresql`); `[plugins]` aliases for ktlint and detekt (used by build-logic's own build); `[bundles]` `kotest` (runner, assertions, property). This file is the only place where a version may appear
- [X] T005 Generate the Gradle wrapper with `gradle wrapper --gradle-version 9.8.0 --distribution-type bin --gradle-distribution-sha256-sum <sha256 from services.gradle.org/distributions/gradle-9.8.0-bin.zip.sha256>` and commit `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`; ensure the properties file contains `distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-bin.zip`, the `distributionSha256Sum=` line and `validateDistributionUrl=true`
- [X] T006 [P] Create `gradle/gradle-daemon-jvm.properties` with `toolchainVersion=25` (generate with `./gradlew updateDaemonJvm --jvm-version=25`) so Gradle itself runs on JDK 25 (FR-002)
- [X] T007 [P] Create `gradle.properties` with exactly: `org.gradle.console=plain`, `org.gradle.logging.level=quiet`, `org.gradle.warning.mode=none`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`, `org.gradle.java.installations.auto-download=false`, `org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC -Dfile.encoding=UTF-8`, `kotlin.code.style=official`, `kotlin.daemon.jvmargs=-Xmx2g`
- [X] T008 Create `settings.gradle.kts`: `rootProject.name = "scalable-e-commerce-platform"`; `pluginManagement { includeBuild("build-logic"); repositories { gradlePluginPortal(); mavenCentral() } }`; `dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }`; a local `fun includeService(name: String)` that calls `include(":services:$name:domain", ":services:$name:application", ":services:$name:infrastructure")`; and the marker line `// --- includeService registry (the newService task appends below this line) ---` (no service registered yet)
- [X] T009 Create `build-logic/settings.gradle.kts`: `rootProject.name = "build-logic"`; `dependencyResolutionManagement { repositories { gradlePluginPortal(); mavenCentral() }; versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } } }`
- [X] T010 Create `build-logic/build.gradle.kts` applying `` `kotlin-dsl` `` plus the ktlint and detekt plugins through catalogue aliases; `kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }`; `dependencies` with `implementation` of the Kotlin Gradle plugin, Spring Boot Gradle plugin, ktlint plugin, detekt plugin and Pitest plugin catalogue libraries, and for tests `testImplementation(libs.bundles.kotest)`, `testImplementation(gradleTestKit())`, `testRuntimeOnly(libs.junit.platform.launcher)`; `tasks.test { useJUnitPlatform(); systemProperty("repo.root", rootDir.parentFile.absolutePath); maxParallelForks = 2 }` (the shared quiet test logging is added in the US1 phase)
- [X] T011 [P] Create `config/detekt/detekt.yml` with `build: { maxIssues: 0 }`, `config: { validation: true }`, `style: { MagicNumber: { active: true, ignoreNumbers: ['-1','0','1','2'] }, MaxLineLength: { maxLineLength: 120 } }`, `complexity: { LongMethod: { threshold: 40 } }` and `potential-bugs: { UnsafeCallOnNullableType: { active: true } }` (the `!!` ban, Principle I); `buildUponDefaultConfig` is set by the quality plugin

**Checkpoint**: `./gradlew help` prints nothing on success and exits 0 (build-logic compiles with zero plugins)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: TestKit harness plus the two internal building blocks every convention uses (`quality`, `kotlin-base`)

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T012 Create `build-logic/src/test/kotlin/com/ecommerce/build/FixtureProject.kt`: a helper `FixtureProject.prepare(name: String, extraFiles: Map<String,String> = emptyMap())` that copies `build-logic/src/test/resources/fixtures/<name>` into a JUnit temp dir, copies the real `gradle/libs.versions.toml`, `.editorconfig`, `.java-version`, `.sdkmanrc`, `config/detekt/detekt.yml`, `gradle/gradle-daemon-jvm.properties` and `gradle/wrapper/gradle-wrapper.properties` (found through system property `repo.root`) into it, appends `dependencyResolutionManagement { repositories { mavenCentral() } }` to the fixture's `settings.gradle.kts`, and offers `run(vararg args: String)` / `runAndFail(vararg args: String)` using `GradleRunner.create().withPluginClasspath().withTestKitDir(build/testkit).withArguments("-q", "--stacktrace", *args)` and returning the combined stdout+stderr plus `BuildResult`; also `stubExecutable(name, script)` that writes an executable shell script to a temp `bin` dir prepended to the runner `PATH` (used for `docker` and `npm`)
- [X] T013 [P] Create fixture `build-logic/src/test/resources/fixtures/quality-clean/` (`settings.gradle.kts` with `rootProject.name = "quality-clean"`, `build.gradle.kts` with `plugins { id("quality") }`, `src/main/kotlin/Clean.kt` containing a ktlint_official-compliant `fun answer(): Int = 42`)
- [X] T014 [P] Create fixture `build-logic/src/test/resources/fixtures/quality-ktlint-violation/` (same layout as quality-clean; `src/main/kotlin/Bad.kt` with a wildcard import `import kotlin.collections.*` and a missing final newline so ktlint reports `Bad.kt`)
- [X] T015 [P] Create fixture `build-logic/src/test/resources/fixtures/quality-detekt-violation/` (same layout; `src/main/kotlin/Risky.kt` using `val n: String? = null; fun len() = n!!.length`, formatted correctly so only detekt `UnsafeCallOnNullableType` fires)
- [X] T016 Write `build-logic/src/test/kotlin/com/ecommerce/build/QualityPluginTest.kt` (Kotest `FunSpec` + `FixtureProject`): quality-clean runs `check` with empty output; quality-ktlint-violation fails `check` and the output names `Bad.kt`; quality-detekt-violation fails `check` and output names `UnsafeCallOnNullableType`; `ktlintCheck` and `detekt` tasks exist in quality-clean. Tests must fail before the plugin exists
- [X] T017 Implement `build-logic/src/main/kotlin/quality.gradle.kts`: apply `org.jlleitschuh.gradle.ktlint` and `dev.detekt` (the detekt release line compatible with Kotlin 2.3.21, pinned in the catalogue); read versions from `the<VersionCatalogsExtension>().named("libs")`; configure ktlint (`version` from catalogue, `ignoreFailures.set(false)`, `reporters` plain only) and detekt (`buildUponDefaultConfig = true`, `config.setFrom(rootProject.file("config/detekt/detekt.yml"))`, `ignoreFailures = false`, console report only); make `check` depend on `ktlintCheck` and `detekt`; both fail the build on any finding (warnings-as-errors, Principle I)
- [X] T018 [P] Create fixture `build-logic/src/test/resources/fixtures/kotlin-base-ok/` (`plugins { id("kotlin-base") }`, `src/main/kotlin/Greeter.kt` with `fun greet(name: String): String = "Hello, $name"`, `src/test/kotlin/GreeterTest.kt` a Kotest `StringSpec` asserting `greet("a") shouldBe "Hello, a"`) plus a root-level task `printToolchain` in its `build.gradle.kts` that prints `toolchain=<languageVersion>` and `allWarningsAsErrors=<value>`
- [X] T019 [P] Create fixture `build-logic/src/test/resources/fixtures/kotlin-base-warning/` (same layout; `src/main/kotlin/Warn.kt` declares `fun f() { val unused = 1 }` so the Kotlin compiler emits an unused-variable warning)
- [X] T020 Write `build-logic/src/test/kotlin/com/ecommerce/build/KotlinBasePluginTest.kt`: kotlin-base-ok runs `test` green and `printToolchain` reports `toolchain=25` and `allWarningsAsErrors=true`; kotlin-base-warning fails `compileKotlin` with the warning text; the fixture's test classpath contains `kotest-runner-junit5`; `archivesName` equals the project path without the leading colon and with `:` replaced by `-`
- [X] T021 Implement `build-logic/src/main/kotlin/kotlin-base.gradle.kts` (internal convention, never applied directly by a module): apply `org.jetbrains.kotlin.jvm` and `quality`; `kotlin { jvmToolchain(<catalogue jdk>); compilerOptions { allWarningsAsErrors.set(true); freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") } }`; `java { toolchain { languageVersion.set(JavaLanguageVersion.of(<catalogue jdk>)) } }` reading the value via `VersionCatalogsExtension`; `base.archivesName.set(project.path.removePrefix(":").replace(':', '-'))`; add `testImplementation` of the `kotest` bundle and `testRuntimeOnly(junit-platform-launcher)`; `tasks.withType<Test> { useJUnitPlatform() }`

**Checkpoint**: Foundation ready; `./gradlew -q :build-logic:check` is not yet wired, run `./gradlew -q -p build-logic test` and see QualityPluginTest and KotlinBasePluginTest green

---

## Phase 3: User Story 1 - Build and verify the whole repository with one command (Priority: P1) MVP

**Goal**: `./gradlew -q verify` on a fresh clone builds and checks everything, is silent on success, shows only the failing step and full assertion on failure, reuses results, works offline, and fails fast on a JDK or Gradle mismatch.

**Independent Test**: In a clean clone with only JDK 25 installed run `./gradlew -q verify` (green, at most 5 output lines); seed a failing test in a throwaway module and confirm only that failure is printed; run again offline and confirm the second run is `UP-TO-DATE`/`FROM-CACHE`.

### Tests for User Story 1

> Write these first and confirm they FAIL before implementation

- [X] T022 [P] [US1] Create fixture `build-logic/src/test/resources/fixtures/verify-repo-green/`: `settings.gradle.kts` including `:libs:alpha` and `:libs:beta`, root `build.gradle.kts` with `plugins { id("repository-root") }`, each module `build.gradle.kts` with `plugins { id("kotlin-base") }` and a passing Kotest `StringSpec` in `src/test/kotlin/`
- [X] T023 [P] [US1] Create fixture `build-logic/src/test/resources/fixtures/verify-repo-failing-test/`: same as verify-repo-green but `:libs:alpha` has a test `"sum"` asserting `(1 + 2) shouldBe 2` and `:libs:beta` has a passing test named `betaPassesQuietly`
- [X] T024 [P] [US1] Create fixture `build-logic/src/test/resources/fixtures/verify-repo-frontend/`: verify-repo-green plus `frontend/package.json` containing `{"name":"frontend","private":true,"scripts":{"lint":"echo lint","test":"echo test"}}`
- [X] T025 [US1] Write `build-logic/src/test/kotlin/com/ecommerce/build/VerifyTaskTest.kt`: verify-repo-green `verify` exits 0 and prints at most 5 lines (assert 0); `verify` task graph contains `:libs:alpha:check`, `:libs:beta:check`, `:checkToolchain` and the included build's `check` (use `--dry-run`); verify-repo-failing-test exits non-zero
- [X] T026 [US1] Write `build-logic/src/test/kotlin/com/ecommerce/build/QuietLoggingTest.kt`: on verify-repo-failing-test the output contains `sum`, `expected:<2> but was:<3>` and the failing module path, does NOT contain `betaPassesQuietly` or `PASSED`, and contains no ANSI escape (`\u001B`); on verify-repo-green the output is empty
- [X] T027 [US1] Write `build-logic/src/test/kotlin/com/ecommerce/build/ToolchainConsistencyTest.kt`: green fixture passes `checkToolchain`; after rewriting the fixture `.java-version` to `24` the build fails with a message containing `Required JDK 25` and `.java-version`; after rewriting `.sdkmanrc` to `java=24.0.2-tem` fails naming `.sdkmanrc`; after rewriting `gradle-wrapper.properties` to `gradle-9.7.0-bin.zip` fails with `Gradle 9.8.0`; after `toolchainVersion=24` in `gradle/gradle-daemon-jvm.properties` fails naming that file
- [X] T028 [US1] Write `build-logic/src/test/kotlin/com/ecommerce/build/FrontendHookTest.kt`: verify-repo-green has no task named `frontendCheck`; verify-repo-frontend with a stubbed `npm` that appends its arguments to `npm-calls.txt` runs `verify` green and `npm-calls.txt` equals `--silent --prefix frontend run lint` then `--silent --prefix frontend run test`; a stub exiting 1 on `test` makes `verify` fail
- [X] T029 [US1] Write `build-logic/src/test/kotlin/com/ecommerce/build/BuildCacheTest.kt`: run `verify` twice on verify-repo-green with a shared `GRADLE_USER_HOME`; second run reports every `:libs:*:test` and `:checkToolchain` outcome as `UP_TO_DATE` or `FROM_CACHE` (via `BuildResult.task(path).outcome`); then run `--offline verify` and assert success

### Implementation for User Story 1

- [X] T030 [US1] Implement `build-logic/src/main/kotlin/com/ecommerce/build/ToolchainConsistencyTask.kt`: a cacheable `DefaultTask` with `@Input` catalogue `jdk` and `gradle` values and `@InputFile`s `.java-version`, `.sdkmanrc`, `gradle/gradle-daemon-jvm.properties`, `gradle/wrapper/gradle-wrapper.properties`; fails with `Required JDK 25 (gradle/libs.versions.toml [versions] jdk) but <file> says <value>` or `Required Gradle 9.8.0 (run ./gradlew) but ...` on any mismatch, and when `GradleVersion.current().version != gradle`
- [X] T031 [US1] Implement `build-logic/src/main/kotlin/repository-root.gradle.kts` (internal convention for the root project): register `checkToolchain` (`ToolchainConsistencyTask`); register lifecycle task `verify` in group `verification` that `dependsOn` `checkToolchain`, `gradle.includedBuild("build-logic").task(":check")` and, lazily, the `check` task of every project that has one (`subprojects { pluginManager.withPlugin("base") { rootProject.tasks.named("verify") { dependsOn(tasks.named("check")) } } }`); when `layout.projectDirectory.file("frontend/package.json").asFile.exists()` register Exec tasks `frontendLint` (`npm --silent --prefix frontend run lint`) and `frontendTest` (`npm --silent --prefix frontend run test`), aggregate lifecycle task `frontendCheck`, and make `verify` depend on `frontendCheck`; expose extension `monorepo { exemptions }` used later by the convention guard
- [X] T032 [US1] Create root `build.gradle.kts` containing only `plugins { id("repository-root") }` and, per FR-013, an empty `monorepo { }` block
- [X] T033 [US1] Extend `build-logic/src/main/kotlin/kotlin-base.gradle.kts` and `build-logic/build.gradle.kts` so every `Test` task sets `testLogging { events(TestLogEvent.FAILED); exceptionFormat = TestExceptionFormat.FULL; showStandardStreams = false; lifecycleLogLevel = LogLevel.QUIET }`, `jvmArgs("-XX:+EnableDynamicAgentLoading")`, and `failOnNoDiscoveredTests.set(false)` (quiet policy of Principle VIII; `lifecycleLogLevel` is raised because FAILED events log at lifecycle level, which `org.gradle.logging.level=quiet` would hide)
- [X] T034 [P] [US1] Create `frontend/README.md` (reserved location only, no application): states that the web storefront is created by a separate feature, that the directory must expose npm scripts `lint` and `test`, and that the root `verify` runs `npm --silent --prefix frontend run lint` and `... run test` as soon as `frontend/package.json` exists (FR-015)
- [X] T035 [US1] Run `./gradlew -q verify` at the repository root; fix any failure until it passes with empty output, then run `./gradlew -q -p build-logic test` and confirm `VerifyTaskTest`, `QuietLoggingTest`, `ToolchainConsistencyTest`, `FrontendHookTest` and `BuildCacheTest` pass

**Checkpoint**: User Story 1 is complete and demonstrable on its own (SC-001 cold/warm timing and SC-002 output size are measured in Polish)

---

## Phase 4: User Story 2 - Shared build conventions declared once (Priority: P1)

**Goal**: Every version lives in the catalogue, every module opts into a named convention and inherits compiler, style, test, logging and mutation settings; version literals, missing conventions, boundary violations and thresholds below 80 fail the build.

**Independent Test**: Change a version in `gradle/libs.versions.toml`, rebuild, see all modules use it; add `implementation("org.example:lib:1.2.3")` to a module script and see `checkVersionLiterals` fail with the file, line and a pointer to the catalogue.

### Tests for User Story 2

> Write these first and confirm they FAIL before implementation

- [X] T036 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/version-literal-violation/`: root `repository-root` plugin; module `:libs:bad` whose `build.gradle.kts` has `implementation("org.example:lib:1.2.3")` (line 4), `id("com.example.plugin") version "1.0.0"` in `plugins {}`, `toolVersion = "0.9.1"` and `jvmToolchain(25)`
- [X] T037 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/version-literal-clean/`: root `repository-root` plugin; module `:libs:good` using `testImplementation(libs.kotest.assertions.core)` and `implementation(libs.kotlinx.coroutines.core)` (catalogue references only)
- [X] T038 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/catalog-single-source/`: modules `:libs:one` and `:libs:two` both applying `kotlin-base` and `testImplementation(libs.kotest.assertions.core)`
- [X] T039 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/convention-missing/`: root `repository-root`; module `:libs:naked` with an empty `build.gradle.kts`; nested group projects `:services` and `:services:demo` without build files plus module `:services:demo:domain` applying `kotlin-domain` (proves group projects are not flagged and nested paths are addressed by full path)
- [X] T040 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/convention-exempt/`: same as convention-missing but the root build declares `monorepo { exempt(":libs:naked", "generated code, no checks wanted") }`
- [X] T041 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/layers-boundaries/`: `settings.gradle.kts` with a local `includeService` function registering `:services:demo:{domain,application,infrastructure}`; domain applies `kotlin-domain`, application `kotlin-application`, infrastructure `kotlin-service`, one-line scripts; each layer has one passing Kotest test in `src/test/kotlin`; plus `src/integrationTest/kotlin/IntegrationSmokeTest.kt`, `src/contractTest/kotlin/ContractSmokeTest.kt`, `src/acceptanceTest/kotlin/AcceptanceSmokeTest.kt` under infrastructure; infrastructure also has `config/architecture/src/test/kotlin/MarkerTest.kt` at the fixture root (a passing test proving the shared architecture source directory is compiled into the infrastructure `test` set)
- [X] T042 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/layers-domain-violation/`: same as layers-boundaries but the domain `build.gradle.kts` adds `implementation(project(":services:demo:application"))`
- [X] T043 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/layers-application-violation/`: same as layers-boundaries but the application `build.gradle.kts` adds `implementation(project(":services:demo:infrastructure"))`
- [X] T044 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/layers-failing-contract/`: layers-boundaries where only `ContractSmokeTest` fails (`1 shouldBe 2`) while the other layers pass
- [X] T045 [P] [US2] Create fixtures `build-logic/src/test/resources/fixtures/pitest-strong/` (domain module with `fun isAdult(age: Int) = age >= 18` and tests at 17, 18, 19 reaching 100 % mutation score), `pitest-weak/` (same function with a single test at 30 so mutants survive, score below 80), `pitest-no-tests/` (domain module with main code and no `src/test`), `pitest-low-threshold/` (strong module whose script sets `pitest { mutationThreshold.set(79) }`), each laid out as `:services:demo:domain` with `kotlin-domain` so the package root is `com.ecommerce.demo.domain`
- [X] T046 [P] [US2] Create fixture `build-logic/src/test/resources/fixtures/docker-image-demo/`: layers-boundaries layout (so `:services:demo:infrastructure` applies `kotlin-service`) plus a placeholder `platform/docker/Dockerfile` containing `FROM scratch`; and `docker-image-no-dockerfile/` identical without the Dockerfile
- [X] T047 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/VersionLiteralCheckTest.kt`: version-literal-violation fails `checkVersionLiterals` and the output contains `build.gradle.kts:4`, `org.example:lib:1.2.3`, `jvmToolchain(25)`, `toolVersion = "0.9.1"`, the plugin version literal and the text `gradle/libs.versions.toml`; version-literal-clean passes; a scan of the real repository (`repo.root`) with the task logic passes; the check is `UP_TO_DATE` on the second run (SC-003, FR-004)
- [X] T048 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/CatalogSingleSourceTest.kt`: run `:libs:one:dependencies --configuration testRuntimeClasspath` and `:libs:two:...`, assert both contain the catalogue's kotest-assertions version; rewrite `[versions] kotest` in the fixture's `gradle/libs.versions.toml` to `6.2.4`, rerun, assert both modules now print `6.2.4` and no module script was edited (AC 2.1)
- [X] T049 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/ConventionGuardTest.kt`: convention-missing fails with a message containing `:libs:naked` and `apply a convention`; group projects `:services` and `:services:demo` are not reported; convention-exempt passes (FR-013)
- [X] T050 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/KotlinDomainPluginTest.kt`: layers-boundaries domain has `test` but no `integrationTest`/`contractTest`/`acceptanceTest` tasks and no Spring on `runtimeClasspath`; layers-domain-violation fails at configuration with a message containing `domain must not depend on` and `:services:demo:application`
- [X] T051 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/KotlinApplicationPluginTest.kt`: application classpath contains the sibling `:services:demo:domain` project automatically; layers-application-violation fails naming `application must not depend on adapters` and `:services:demo:infrastructure`; no Spring on its `runtimeClasspath`
- [X] T052 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/KotlinServicePluginTest.kt`: infrastructure has tasks `test`, `integrationTest`, `contractTest`, `acceptanceTest` and `check` depends on all four; `:services:demo:infrastructure:integrationTest` runs alone and executes only the integration smoke test; layers-failing-contract fails `contractTest` only and the output names `ContractSmokeTest` and not the other layers; an infrastructure module with an empty layer passes ("no tests"); `MarkerTest` from `config/architecture` runs inside the `test` task; ktlintCheck and detekt tasks exist (style convention inherited, AC 2.2)
- [X] T053 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/PitestPluginTest.kt`: pitest-strong passes `pitest` with the default threshold 80; pitest-weak fails with output containing `Mutation score` and `80`; pitest-no-tests passes and the `pitest` task is `SKIPPED`; pitest-low-threshold fails at configuration with `below the constitution minimum 80` (SC-007)
- [X] T054 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/PactPluginTest.kt`: on layers-boundaries the `contractTest` runtime classpath contains `pact-consumer-junit5` and `pact-provider-junit5`; the `contractTest` task has system properties `pact.rootDir` ending `build/pacts` and `pact.writer.overwrite=true`, and `junit.jupiter.testclass.order.default` equals `org.junit.jupiter.api.ClassOrderer$OrderAnnotation`
- [X] T055 [US2] Write `build-logic/src/test/kotlin/com/ecommerce/build/DockerImagePluginTest.kt`: with a stub `docker` script recording its arguments, `:services:demo:infrastructure:dockerImage` on docker-image-demo records `build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=:services:demo:infrastructure -t demo:<project version> .`; docker-image-no-dockerfile fails with `platform/docker/Dockerfile not found`; `dockerImage` is not a dependency of `check` or `verify` (`--dry-run`)

### Implementation for User Story 2

- [X] T056 [US2] Implement `build-logic/src/main/kotlin/com/ecommerce/build/TestLayers.kt`: `fun Project.registerTestLayer(name: String): NamedDomainObjectProvider<JvmTestSuite>` (idempotent: returns the existing suite if present) registering a `jvm-test-suite` named `integrationTest`/`contractTest`/`acceptanceTest` with `useJUnitJupiter()`, `testType` from `TestSuiteType`, the main output on its classpath, `testImplementation` dependencies inherited via `configurations.named("${name}Implementation") { extendsFrom(configurations["testImplementation"]) }`, `shouldRunAfter` the previous layer, `failOnNoDiscoveredTests = false`, and `tasks.named("check") { dependsOn(suite) }`
- [X] T057 [P] [US2] Create `build-logic/src/main/kotlin/com/ecommerce/build/QualityThresholds.kt`: `object QualityThresholds { const val MINIMUM_MUTATION_THRESHOLD = 80 }` (the single declaration of the constitution's Principle VIII floor)
- [X] T058 [US2] Implement `build-logic/src/main/kotlin/com/ecommerce/build/CheckVersionLiteralsTask.kt`: cacheable `DefaultTask` with `@InputFiles` file tree `**/*.gradle.kts` (excluding `**/build/**`, `**/.gradle/**`, `**/node_modules/**`, `build-logic/src/test/resources/fixtures/**`), `@OutputFile` marker `build/version-literals/ok`; applies the four regexes from research.md section 3 per line and throws `GradleException` listing every hit as `<relative path>:<line>: version literal '<text>'; declare it in gradle/libs.versions.toml and reference it through the catalogue`
- [X] T059 [US2] Implement `build-logic/src/main/kotlin/com/ecommerce/build/ConventionGuard.kt`: `fun Project.verifyConventions(exemptions: Map<String, String>)` run from `gradle.projectsEvaluated`; for every leaf project (no subprojects, not the root) require `pluginManager.hasPlugin("kotlin-base")` or an entry in `exemptions`; otherwise throw `GradleException("Module <path> applies no convention; apply one of kotlin-domain, kotlin-application, kotlin-service or exempt it with monorepo { exempt(\"<path>\", \"<reason>\") }")`; group projects with children are ignored
- [X] T060 [US2] Edit `build-logic/src/main/kotlin/repository-root.gradle.kts`: register `checkVersionLiterals` (`CheckVersionLiteralsTask`), make `verify` depend on it, add `monorepo { fun exempt(path, reason) }` storing exemptions, and call `verifyConventions` from `gradle.projectsEvaluated`
- [X] T061 [US2] Implement `build-logic/src/main/kotlin/pitest.gradle.kts`: apply `info.solidsoft.pitest`; configure `pitestVersion` and `junit5PluginVersion` from the catalogue; `targetClasses.set(setOf("com.ecommerce.${serviceName}.${layer}.*"))` derived from project path (`:services:<name>:<layer>`); `mutationThreshold.convention(QualityThresholds.MINIMUM_MUTATION_THRESHOLD)`; `threads.set(Runtime.getRuntime().availableProcessors())`; `outputFormats.set(setOf("XML","HTML"))`, `timestampedReports.set(false)`, `verbose.set(false)`, `failWhenNoMutations.set(false)`, `enableDefaultIncrementalAnalysis.set(true)`; exclusions `excludedClasses` (`*$WhenMappings`, `*$DefaultImpls`, `*Kt$*$1`), `avoidCallsTo` (`kotlin.jvm.internal`, `kotlin.Intrinsics`, `kotlinx.coroutines`), `excludedMethods` (`toString`, `hashCode`, `equals`, `copy`, `component*`); in `afterEvaluate` throw `GradleException("Mutation threshold <n> is below the constitution minimum 80 (Principle VIII)")` when the configured value is below the floor; `tasks.named("pitest") { onlyIf { sourceSets["test"].allSource.files.any { it.isFile } } }`; `tasks.named("check") { dependsOn("pitest") }`
- [X] T062 [US2] Implement `build-logic/src/main/kotlin/kotlin-domain.gradle.kts`: apply `kotlin-base` and `pitest`; `afterEvaluate` fail with `domain must not depend on <path>` when any `implementation`/`api` dependency is a `ProjectDependency`, and when any dependency group starts with `org.springframework`, `io.r2dbc`, `org.flywaydb` or `jakarta`
- [X] T063 [US2] Implement `build-logic/src/main/kotlin/kotlin-application.gradle.kts`: apply `kotlin-base` and `pitest`; add `implementation(project(project.parent!!.path + ":domain"))`, `implementation(libs.kotlinx.coroutines.core)` and `testImplementation(libs.mockk)`; `afterEvaluate` fail with `application must not depend on adapters (<path>)` for any project dependency other than the sibling domain and for any `org.springframework` dependency
- [X] T064 [P] [US2] Implement `build-logic/src/main/kotlin/pact.gradle.kts`: call `registerTestLayer("contractTest")`; add `contractTestImplementation` of `pact-consumer-junit5` and `pact-provider-junit5` (Pact JVM `4.7.5`); set on the `contractTest` task `systemProperty("pact.rootDir", layout.buildDirectory.dir("pacts").get().asFile.path)`, `systemProperty("pact.writer.overwrite", "true")`, `systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")` and `systemProperty("pact.verifier.publishResults", "false")`
- [X] T065 [P] [US2] Implement `build-logic/src/main/kotlin/docker-image.gradle.kts` and `platform/docker/Dockerfile` plus root `.dockerignore`: `dockerImage` task (type `Exec`, group `build`) with `dockerExecutable` property default `docker`, working dir the root project directory, args `build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=<project.path> -t <parent project name>:<project.version> .`, failing with `platform/docker/Dockerfile not found` when the file is absent, never wired into `check`; Dockerfile multi-stage: stage `build` `FROM eclipse-temurin:25-jdk@sha256:<digest resolved at implementation>` runs `./gradlew -q ${SERVICE_MODULE}:bootJar` with `--mount=type=cache,target=/root/.gradle`; stage `layers` extracts with `java -Djarmode=tools -jar app.jar extract --layers --launcher`; final stage `FROM eclipse-temurin:25-jre@sha256:<digest>` creates non-root user `app` (uid 10001), copies layers, sets `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"`, `HEALTHCHECK CMD` calling `http://localhost:8081/actuator/health`, `USER app`; `.dockerignore` excludes `**/build`, `.gradle`, `.git`, `frontend/node_modules`
- [X] T066 [US2] Implement `build-logic/src/main/kotlin/kotlin-service.gradle.kts`: apply `kotlin-base`, `org.jetbrains.kotlin.plugin.spring`, `org.springframework.boot`, `pact` and `docker-image`; import `platform(SpringBootPlugin.BOM_COORDINATES)`; `implementation(project(parent ":domain"))`, `implementation(project(parent ":application"))`; baseline `implementation` of webflux, actuator, data-r2dbc, flyway starter, `flyway-database-postgresql`, `kotlinx-coroutines-reactor`, `jackson-module-kotlin`, `micrometer-registry-prometheus`, `context-propagation`, `r2dbc-postgresql`; `runtimeOnly(postgresql)`; register `integrationTest` and `acceptanceTest` through `registerTestLayer` (contract comes from `pact`); add to every layer `testImplementation` of `spring-boot-starter-webflux-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`, `wiremock`, `mockk`, and to `acceptanceTest` `cucumber-java`, `cucumber-junit-platform-engine`, `junit-platform-suite`; add `konsist` to `testImplementation`; if `rootProject.file("config/architecture/src/test/kotlin")` exists add it to the `test` source set (`kotlin.sourceSets["test"].kotlin.srcDir(...)`) and set `test` system properties `architecture.serviceRoot` (the service directory) and `architecture.basePackage` (`com.ecommerce.<service>`); `afterEvaluate` fail with `service module <path> must be named infrastructure` otherwise; `springBoot { buildInfo() }`
- [X] T067 [US2] Run `./gradlew -q -p build-logic test` and fix until `VersionLiteralCheckTest`, `CatalogSingleSourceTest`, `ConventionGuardTest`, `KotlinDomainPluginTest`, `KotlinApplicationPluginTest`, `KotlinServicePluginTest`, `PitestPluginTest`, `PactPluginTest` and `DockerImagePluginTest` pass; then run `./gradlew -q verify` at the root and confirm it is green and silent

**Checkpoint**: Conventions exist, are tested, and the root build enforces literals and convention usage

---

## Phase 5: User Story 3 - Reference service demonstrating the mandated architecture (Priority: P2)

**Goal**: `services/catalog` (bounded context "catalogue") with domain, application and infrastructure modules, four passing test layers, Konsist rules, mutation score at or above 80, health endpoint, structured logs and metrics, and no business logic.

**Independent Test**: Add `import org.springframework.stereotype.Component` to a domain file and see the build fail naming that file (it fails first at the domain's `compileKotlin` with an unresolved reference, since the module graph keeps Spring off the domain classpath; the Konsist rule, which names the rule as well, is proven by `ArchitectureRulesSeededViolationTest`); run `./gradlew -q :services:catalog:infrastructure:contractTest` and see only the contract layer run.

### Tests for User Story 3

> Write these first and confirm they FAIL (do not compile or assert) before implementation

- [X] T068 [P] [US3] Write `services/catalog/domain/src/test/kotlin/com/ecommerce/catalog/domain/ServiceNameTest.kt` (Kotest `FunSpec`, property tests): `checkAll(Arb.stringPattern("[a-z][a-z0-9-]{1,38}[a-z0-9]"))` yields `ServiceNameResult.Valid` whose `name.value` equals the input; generated strings containing uppercase, starting with a digit or `-`, shorter than 3 or longer than 40 characters yield `Invalid`; documented edge cases: `"abc"` (3 chars) valid, `"ab"` invalid, a 40-character string valid, a 41-character string invalid, blank invalid
- [X] T069 [P] [US3] Write `services/catalog/domain/src/test/kotlin/com/ecommerce/catalog/domain/HealthStatusTest.kt`: property test that `HealthStatus.down("  ")` (blank reason) is rejected and any non-blank reason is preserved; `Up` is a singleton `object`; `combine(listOf(...))` of only `Up` is `Up`, otherwise `Down` with all reasons joined by `"; "` in order
- [X] T070 [P] [US3] Write `services/catalog/application/src/test/kotlin/com/ecommerce/catalog/application/CheckServiceHealthTest.kt`: Kotest property tests with MockK `HealthProbe` doubles (ports only): all probes `Up` gives `HealthReport` with `Up`; any `Down(reason)` gives `Down` containing every failing reason; zero probes gives `Up`; all probes are invoked even after a failure (`coVerify(exactly = 1)` for each)
- [X] T071 [P] [US3] Write `config/architecture/src/test/kotlin/com/ecommerce/architecture/ArchitectureRulesSeededViolationTest.kt`: creates temporary service roots (`<tmp>/domain/src/main/kotlin/Bad.kt`, `<tmp>/application/src/main/kotlin/Bad.kt`) with a domain file importing `org.springframework.stereotype.Component` and an application file importing `com.ecommerce.catalog.infrastructure.CatalogApplication`; asserts `ArchitectureRules.check(...)` returns violations whose message is `Rule 'domain must not import frameworks' violated by <path>/Bad.kt: import org.springframework.stereotype.Component` (names file and rule) and a similar one for the application rule; asserts a clean temp tree yields no violations (SC-005)
- [X] T072 [P] [US3] Write `config/architecture/src/test/kotlin/com/ecommerce/architecture/ArchitectureTest.kt`: reads `architecture.serviceRoot` and `architecture.basePackage` system properties, runs `ArchitectureRules.check` against `<serviceRoot>/domain/src/main` and `<serviceRoot>/application/src/main` and fails the test with the joined violation messages when any exist
- [X] T073 [P] [US3] Create `services/catalog/infrastructure/src/integrationTest/kotlin/com/ecommerce/catalog/infrastructure/PostgresContainerConfig.kt`: `@TestConfiguration(proxyBeanMethods = false)` exposing `@Bean @ServiceConnection fun postgres() = PostgreSQLContainer("postgres:18-alpine")` (ephemeral credentials only)
- [X] T074 [US3] Write `services/catalog/infrastructure/src/integrationTest/kotlin/com/ecommerce/catalog/infrastructure/HealthEndpointIntegrationTest.kt`: `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])` with `PostgresContainerConfig` and `WebTestClient`: `GET /actuator/health` returns 200 and `{"status":"UP"}`; after pausing the container (`postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()`, unpaused in `@AfterEach`) it returns 503 and `{"status":"DOWN"}` within 5 seconds
- [X] T075 [P] [US3] Write `services/catalog/infrastructure/src/integrationTest/kotlin/com/ecommerce/catalog/infrastructure/FlywayBaselineIntegrationTest.kt`: after context start, `DatabaseClient` reads `flyway_schema_history` and asserts version `1` with description `baseline` succeeded, and that table `schema_marker` exists
- [X] T076 [P] [US3] Write `services/catalog/infrastructure/src/integrationTest/kotlin/com/ecommerce/catalog/infrastructure/MetricsAndCorrelationIntegrationTest.kt`: `GET /actuator/prometheus` returns 200 and contains `jvm_memory_used_bytes`; a request with header `X-Correlation-Id: abc-123` gets the same header echoed; a request without it gets a generated UUID header; captured console output (`OutputCaptureExtension`) for that request is one JSON line containing `"correlationId":"abc-123"`
- [X] T077 [P] [US3] Create `services/catalog/infrastructure/src/contractTest/kotlin/com/ecommerce/catalog/infrastructure/PostgresContainerConfig.kt` (same content as the integration-layer config, own package-private copy because layers do not share classes)
- [X] T078 [US3] Write `services/catalog/infrastructure/src/contractTest/kotlin/com/ecommerce/catalog/infrastructure/HealthConsumerPactTest.kt`: `@ExtendWith(PactConsumerTestExt::class) @PactTestFor(providerName = "catalog", pactVersion = V4)` `@Order(1)` consumer `platform-probe` expecting `GET /actuator/health` with 200 and body `{"status":"UP"}` (`LambdaDsl`), writing `build/pacts/platform-probe-catalog.json`
- [X] T079 [US3] Write `services/catalog/infrastructure/src/contractTest/kotlin/com/ecommerce/catalog/infrastructure/HealthProviderVerificationTest.kt`: `@Order(2) @Provider("catalog") @PactFolder("build/pacts")` with `@SpringBootTest(webEnvironment = RANDOM_PORT)` plus `PostgresContainerConfig`, `HttpTestTarget("localhost", port)` and `@TestTemplate @ExtendWith(PactVerificationInvocationContextProvider::class)`; add `services/catalog/infrastructure/src/contractTest/resources/junit-platform.properties` only if the convention's class-order property is not picked up
- [X] T080 [P] [US3] Create `services/catalog/infrastructure/src/acceptanceTest/kotlin/com/ecommerce/catalog/infrastructure/PostgresContainerConfig.kt` (same content, own copy)
- [X] T081 [US3] Write `services/catalog/infrastructure/src/acceptanceTest/resources/features/service-status.feature` in business language only (no endpoints, verbs, tables or classes): `Feature: Service status`, `Scenario: Operator checks that the catalogue service is available` with `Given the catalogue service is running`, `When an operator asks whether the service is healthy`, `Then the service reports that it is healthy`; `Scenario: Operator sees that the catalogue service is unavailable when its storage is down` with `Given the catalogue service is running`, `And its storage becomes unreachable`, `When an operator asks whether the service is healthy`, `Then the service reports that it is unhealthy`
- [X] T082 [US3] Write `services/catalog/infrastructure/src/acceptanceTest/kotlin/com/ecommerce/catalog/infrastructure/ServiceStatusSteps.kt` (Cucumber step definitions bound with `@CucumberContextConfiguration @SpringBootTest(webEnvironment = RANDOM_PORT)` and `PostgresContainerConfig`; the only place that knows `/actuator/health`; the step `its storage becomes unreachable` pauses the injected `PostgreSQLContainer` and an `@After` hook unpauses it) and `services/catalog/infrastructure/src/acceptanceTest/kotlin/com/ecommerce/catalog/infrastructure/AcceptanceSuite.kt` (`@Suite @IncludeEngines("cucumber") @SelectClasspathResource("features")`)

### Implementation for User Story 3

- [X] T083 [P] [US3] Implement `services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/ServiceName.kt`: `@JvmInline value class ServiceName private constructor(val value: String)` with `companion object { fun of(raw: String): ServiceNameResult }` validating regex `[a-z][a-z0-9-]{1,38}[a-z0-9]`; `sealed interface ServiceNameResult { data class Valid(val name: ServiceName); data class Invalid(val reason: String) }`; and `services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/HealthStatus.kt`: `sealed interface HealthStatus { object Up; data class Down(val reason: String) with require(reason.isNotBlank()) ; companion fun combine(statuses: List<HealthStatus>): HealthStatus }` (pure Kotlin, no imports outside `kotlin.*`)
- [X] T084 [P] [US3] Implement `services/catalog/application/src/main/kotlin/com/ecommerce/catalog/application/HealthProbe.kt` (`fun interface HealthProbe { suspend fun check(): HealthStatus }`), `HealthReport.kt` (`data class HealthReport(val service: ServiceName, val status: HealthStatus)`) and `CheckServiceHealth.kt` (`class CheckServiceHealth(private val service: ServiceName, private val probes: List<HealthProbe>) { suspend operator fun invoke(): HealthReport }` evaluating every probe and combining statuses) in the same package directory
- [X] T085 [US3] Create the three module scripts `services/catalog/domain/build.gradle.kts` (`plugins { id("kotlin-domain") }`), `services/catalog/application/build.gradle.kts` (`plugins { id("kotlin-application") }`), `services/catalog/infrastructure/build.gradle.kts` (`plugins { id("kotlin-service") }`) and register the service by adding `includeService("catalog")` below the marker line in `settings.gradle.kts`
- [X] T086 [US3] Implement the Spring Boot application under `services/catalog/infrastructure/src/main/kotlin/com/ecommerce/catalog/infrastructure/`: `CatalogApplication.kt` (`@SpringBootApplication`, `main` calls `Hooks.enableAutomaticContextPropagation()` then `runApplication`), `DatabaseHealthProbe.kt` (outbound adapter implementing `HealthProbe` with `DatabaseClient.sql("SELECT 1")` inside `withTimeout(2.seconds)`, returning `Down("database unreachable")` on error or timeout), `HealthIndicatorAdapter.kt` (`ReactiveHealthIndicator` calling `CheckServiceHealth` and mapping `HealthStatus` to Actuator `Health`), `UseCaseConfiguration.kt` (`@Configuration` creating `ServiceName.of("catalog")` and the `CheckServiceHealth` bean), `CorrelationIdWebFilter.kt` (`WebFilter`, header `X-Correlation-Id`, generates a UUID when absent or longer than 64 characters or not matching `[A-Za-z0-9-]+`, echoes it in the response, writes it to the Reactor context key `correlationId` and registers a `ThreadLocalAccessor` with `ContextRegistry` mirroring it into MDC `correlationId`)
- [X] T087 [US3] Create `services/catalog/infrastructure/src/main/resources/application.yml` (`spring.application.name: catalog`; `spring.r2dbc.url: r2dbc:postgresql://${CATALOG_DB_HOST:localhost}:5432/catalog`, `username: ${CATALOG_DB_USER}`, `password: ${CATALOG_DB_PASSWORD}` with no committed defaults; `spring.flyway.url: jdbc:postgresql://${CATALOG_DB_HOST:localhost}:5432/catalog` with the same user and password and a comment marking the blocking-at-boot exception (Principle IV, plan Complexity Tracking); `management.server.port: 8081`; `management.endpoints.web.exposure.include: health,prometheus`; `management.endpoint.health.show-details: never`; `logging.structured.format.console: ecs`) and `services/catalog/infrastructure/src/main/resources/db/migration/V1__baseline.sql` (`CREATE TABLE schema_marker (id integer PRIMARY KEY);`)
- [X] T088 [US3] Implement `config/architecture/src/test/kotlin/com/ecommerce/architecture/ArchitectureRules.kt`: `object ArchitectureRules { fun check(serviceRoot: Path, basePackage: String): List<String> }` using Konsist `scopeFromDirectory` for `<serviceRoot>/domain/src/main` and `<serviceRoot>/application/src/main`; rule `domain must not import frameworks` (imports starting `org.springframework`, `io.r2dbc`, `jakarta`, `org.flywaydb`, `<basePackage>.application`, `<basePackage>.infrastructure`) and rule `application must not import adapters or frameworks` (imports starting `<basePackage>.infrastructure`, `org.springframework`); message format `Rule '<rule>' violated by <file path>: import <import>`
- [X] T089 [P] [US3] Create `services/catalog/README.md` (module README required by the Definition of Done): purpose (reference service for bounded context "catalogue", no business logic), the three modules and their allowed dependencies, the four test layers with their single-layer commands, required environment variables `CATALOG_DB_HOST`, `CATALOG_DB_USER`, `CATALOG_DB_PASSWORD`, health at `:8081/actuator/health`, metrics at `:8081/actuator/prometheus`
- [X] T090 [US3] Run `./gradlew -q :services:catalog:domain:check :services:catalog:application:check :services:catalog:infrastructure:check` and fix until green and silent; confirm `services/catalog/domain/build/reports/pitest/` and `.../application/build/reports/pitest/` show a mutation score of at least 80 and that `:services:catalog:infrastructure:contractTest` and `:acceptanceTest` each run alone

**Checkpoint**: The reference service is complete; quickstart sections 5 and 6 (seeded violation, single layer) pass

---

## Phase 6: User Story 4 - Add a new service in minutes (Priority: P2)

**Goal**: `./gradlew newService -Pname=<context>` scaffolds a service identical in structure to `catalog`, registers it, and `./gradlew -q verify` passes with it before any business code exists.

**Independent Test**: Run `./gradlew newService -Pname=orders` then `./gradlew -q verify` in under 15 minutes, touching only `settings.gradle.kts` among existing files.

### Tests for User Story 4

> Write these first and confirm they FAIL before implementation

- [X] T091 [P] [US4] Create fixture `build-logic/src/test/resources/fixtures/scaffold-repo/`: root `repository-root` plugin, a `settings.gradle.kts` with the local `includeService` function, the marker line `// --- includeService registry (the newService task appends below this line) ---` and the `includeService("demo")` pre-registration of an existing service copied from the layers-boundaries layout
- [X] T092 [US4] Write `build-logic/src/test/kotlin/com/ecommerce/build/NewServiceTaskTest.kt`: `newService -Pname=orders` on scaffold-repo creates `services/orders/{domain,application,infrastructure}/build.gradle.kts` each exactly one line `plugins { id("kotlin-domain") }` / `kotlin-application` / `kotlin-service`, appends `includeService("orders")` below the marker (no other line of `settings.gradle.kts` changes), and a following `-q verify` passes; invalid names (`Orders`, `order-items`, `1st`, empty) fail with `name must match [a-z][a-z0-9]*`; an existing service name fails with `already exists`; running without `-Pname` fails with `-Pname=<context> is required`
- [X] T093 [US4] Write `build-logic/src/test/kotlin/com/ecommerce/build/ServiceLayoutParityTest.kt`: scaffolds `orders` into a copy of scaffold-repo and compares the sorted set of relative directory paths (and file names of one-line scripts, `application.yml`, `V1__baseline.sql`, application class) with `services/catalog` from the real repository (`repo.root`) after substituting `catalog` to `orders`; the two sets must be equal for the directories `{domain,application,infrastructure}/src/{main,test}` and `infrastructure/src/{integrationTest,contractTest,acceptanceTest}` (AC 4.3)

### Implementation for User Story 4

- [X] T094 [US4] Create the scaffold templates under `build-logic/src/main/resources/service-template/` with placeholders `__name__` and `__Name__`: `domain/build.gradle.kts`, `application/build.gradle.kts`, `infrastructure/build.gradle.kts` (one line each), `infrastructure/src/main/kotlin/com/ecommerce/__name__/infrastructure/__Name__Application.kt` (`@SpringBootApplication` + `main`), `infrastructure/src/main/resources/application.yml` (copy of catalog's with `catalog` replaced by `__name__`), `infrastructure/src/main/resources/db/migration/V1__baseline.sql`, and `.gitkeep` files in `domain/src/{main,test}/kotlin`, `application/src/{main,test}/kotlin`, `infrastructure/src/{test,integrationTest,contractTest,acceptanceTest}/kotlin`
- [X] T095 [US4] Implement `build-logic/src/main/kotlin/com/ecommerce/build/ScaffoldServiceTask.kt`: `DefaultTask` reading `-Pname` from `project.providers.gradleProperty("name")`, validating `^[a-z][a-z0-9]*$`, refusing an existing `services/<name>`, copying the templates replacing `__name__` and `__Name__` (capitalised), and appending `includeService("<name>")` after the marker line in `settings.gradle.kts`; error messages exactly as asserted in NewServiceTaskTest
- [X] T096 [US4] Edit `build-logic/src/main/kotlin/repository-root.gradle.kts` to register task `newService` (group `build setup`, type `ScaffoldServiceTask`, description `Scaffold services/<name> with domain, application and infrastructure modules; usage: ./gradlew newService -Pname=<context>`)
- [X] T097 [US4] Run `./gradlew -q -p build-logic test` (NewServiceTaskTest and ServiceLayoutParityTest green) and execute the quickstart "add a service" walkthrough in a scratch copy of the repository: `./gradlew newService -Pname=orders && ./gradlew -q verify`, then discard the copy

**Checkpoint**: A new service passes `verify` with zero business code

---

## Phase 7: User Story 5 - Build documentation and CI readiness (Priority: P3)

**Goal**: Documentation explains layout, verify, layers, adding modules and dependencies and the gate mapping; a GitHub Actions workflow runs `./gradlew -q verify` on every pull request.

**Independent Test**: Follow `docs/build.md` to add a dependency to `services/catalog/application` and run only that module's unit tests with the documented command; open a pull request and see the `verify` check report.

### Tests for User Story 5

> Write these first and confirm they FAIL before implementation

- [X] T098 [US5] Write `build-logic/src/test/kotlin/com/ecommerce/build/RepositoryDocsAndCiTest.kt` reading the real repository through `repo.root`: `docs/build.md` has second-level headings `## Layout`, `## Verify command`, `## Running a test layer`, `## Adding a module`, `## Adding a dependency`, `## Quality gates` and contains the copy-pasteable commands `./gradlew -q verify`, `./gradlew -q :services:catalog:infrastructure:integrationTest`, `./gradlew newService -Pname=` and `gradle/libs.versions.toml`; `README.md` links to `docs/build.md`; `.github/workflows/verify.yml` triggers on `pull_request` and `push` to `main`, has `timeout-minutes: 15`, `permissions:` with only `contents: read`, runs the exact step `./gradlew -q verify`, guards forks (`github.event.pull_request.head.repo.full_name == github.repository`), and every `uses:` reference is pinned to a 40-hex commit SHA; `frontend/README.md` exists

### Implementation for User Story 5

- [X] T099 [P] [US5] Create `docs/build.md` with sections `## Layout` (tree from plan.md), `## Verify command` (`./gradlew -q verify`, requirements JDK 25 and a Docker-API engine, expected silence, offline behaviour), `## Running a test layer` (`./gradlew -q :services:catalog:infrastructure:test|integrationTest|contractTest|acceptanceTest` and the whole-repository forms `./gradlew -q integrationTest`), `## Adding a module` (`./gradlew newService -Pname=<context>`, the one-line scripts, the only existing file edited is `settings.gradle.kts`), `## Adding a dependency` (add to `gradle/libs.versions.toml`, reference as `libs.<alias>`, version literals fail `checkVersionLiterals`), `## Quality gates` (table: per-file hook gate runs `./gradlew -q :<module>:ktlintCheck :<module>:detekt :<module>:test`; end-of-task gate and pull-request gate run `./gradlew -q verify`; image build `./gradlew :services:catalog:infrastructure:dockerImage` is outside verify), `## Mutation testing` (threshold 80 floor, exclusions, Arcmutate follow-up), `## Blocking exception` (Flyway at boot) and `## Troubleshooting` (JDK mismatch message, Docker not running)
- [X] T100 [P] [US5] Create root `README.md`: one-paragraph project description, prerequisites (JDK 25 via `sdk env` or `.java-version`, Docker-compatible engine), the single command `./gradlew -q verify`, and a link to `docs/build.md` and `specs/`
- [X] T101 [US5] Create `.github/workflows/verify.yml`: `name: verify`; `on: { pull_request: {}, push: { branches: [main] } }`; `permissions: { contents: read }`; `concurrency: { group: verify-${{ github.ref }}, cancel-in-progress: true }`; job `verify` with `runs-on: [self-hosted, linux]`, `timeout-minutes: 15`, `if: github.event_name == 'push' || github.event.pull_request.head.repo.full_name == github.repository`; steps `actions/checkout@<40-hex SHA of the current v4/v5 release resolved at implementation>`, `actions/setup-java@<SHA>` with `distribution: temurin` and `java-version-file: .java-version`, `gradle/actions/setup-gradle@<SHA>` with `cache-read-only: ${{ github.ref != 'refs/heads/main' }}`, and `run: ./gradlew -q verify`; add a comment that ephemeral runner registration is covered by feature 004
- [X] T102 [US5] Run `./gradlew -q -p build-logic test --tests '*RepositoryDocsAndCiTest'` green, then execute the documented "add a dependency and run one module's unit tests" procedure from `docs/build.md` verbatim in a scratch change and discard it

**Checkpoint**: All five stories are independently complete

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Success-criteria validation and follow-ups spanning stories

- [X] T103 Measure SC-001 and SC-002: from a clean clone with an empty Gradle user home run `time ./gradlew -q verify` (must finish in under 10 minutes), then `time ./gradlew -q verify` again (under 2 minutes), and `./gradlew -q verify | wc -l` (at most 5); record the numbers in `docs/build.md` under `## Measured timings`
- [X] T104 [P] Verify SC-003 and SC-005: `grep -rEn '"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][^"]*"' --include='*.gradle.kts' . | grep -v fixtures` returns nothing; add `import org.springframework.stereotype.Component` to `services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/ServiceName.kt`, run `./gradlew -q :services:catalog:infrastructure:test` and confirm failure naming that file and the rule, then revert the edit
- [X] T105 [P] Verify SC-006 and SC-007: run `./gradlew -q :services:catalog:infrastructure:integrationTest`, `:contractTest`, `:acceptanceTest` and `:test` one at a time and confirm each reports separately; temporarily set `pitest { mutationThreshold.set(79) }` in `services/catalog/domain/build.gradle.kts`, confirm the build fails with `below the constitution minimum 80`, revert
- [X] T106 [P] Check that `.claude/hooks/post-edit-check.sh` and `.claude/hooks/stop-full-check.sh` locate `./gradlew` and that `./gradlew -q verify` is what the end-of-task gate runs (read-only; if they call a different task, record the required change in `docs/build.md` under `## Quality gates` instead of editing specs/001)
- [X] T107 [P] Update `CLAUDE.md` with a short "Build" section listing `./gradlew -q verify`, the four single-layer commands for a module and `./gradlew newService -Pname=<context>`
- [X] T108 Run `./gradlew :services:catalog:infrastructure:dockerImage` once with a Docker engine; confirm the image builds, runs as a non-root user (`docker run --rm --entrypoint id catalog:<version>` reports uid 10001) and record the image size in `docs/build.md`
- [X] T109 [P] Record the non-blocking follow-ups in `docs/build.md` under `## Follow-ups`: Arcmutate `pitest-kotlin-plugin` licence enquiry for this public MIT repository (outcome and the one-line switch in `build-logic/src/main/kotlin/pitest.gradle.kts`), PATCH amendment of the constitution's "Spring Boot 3.x" wording to "current GA major" via `/speckit-constitution`, Dockerfile base-image digests and workflow action SHAs refresh cadence
- [X] T110 Run every procedure in `specs/002-gradle-monorepo-bootstrap/quickstart.md` in order and tick the SC-001..SC-008 mapping table; SC-008 is confirmed on the first pull request that triggers `.github/workflows/verify.yml`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies; start immediately. T004 and T005 precede everything else that runs Gradle
- **Foundational (Phase 2)**: Depends on Setup; blocks all user stories (T012, `quality`, `kotlin-base`)
- **US1 (Phase 3, P1)**: Depends on Foundational only; delivers `verify`, quiet logging, toolchain guard, frontend hook
- **US2 (Phase 4, P1)**: Depends on Foundational and on the `repository-root` plugin from US1 (T031); delivers all conventions
- **US3 (Phase 5, P2)**: Depends on US2 (conventions) and on US1 (`verify`)
- **US4 (Phase 6, P2)**: Depends on US3 (the scaffold mirrors `services/catalog`) and US2
- **US5 (Phase 7, P3)**: Documentation tasks depend on US3/US4 behaviour being final; the workflow depends only on US1
- **Polish (Phase 8)**: Depends on all desired stories

### User Story Dependencies

- **US1**: independent after Foundational (verified against fixtures, not against real services)
- **US2**: extends the root plugin created in US1 (same file, sequential edits) but is tested through its own fixtures
- **US3**: consumes every convention from US2
- **US4**: consumes the reference service structure from US3
- **US5**: independent in content, last in priority

### Within Each User Story

- Fixtures before the tests that use them; tests before implementation and confirmed failing
- Helper classes (`TestLayers.kt`, `QualityThresholds.kt`) before the convention scripts that import them
- Edits to the same file (`repository-root.gradle.kts`: T031, T060, T096; `kotlin-base.gradle.kts`: T021, T033; `settings.gradle.kts`: T008, T085) are never marked [P] and run in the order listed

### Parallel Opportunities

- Setup: T001, T002, T003, T006, T007, T011
- Foundational fixtures: T013, T014, T015, T018, T019
- US1 fixtures: T022, T023, T024; US2 fixtures (all `fx*` tasks) and the three helper implementations T057, T064, T065
- US3: unit tests T068, T069, T070, T071, T072 and domain/application implementations T083, T084
- US5: T099 and T100; Polish: T104, T105, T106, T107, T109

---

## Parallel Example: User Story 2

```bash
# Launch all US2 fixtures together:
Task: "Create fixture version-literal-violation in build-logic/src/test/resources/fixtures/version-literal-violation/"
Task: "Create fixture convention-missing in build-logic/src/test/resources/fixtures/convention-missing/"
Task: "Create fixtures pitest-strong/weak/no-tests/low-threshold in build-logic/src/test/resources/fixtures/"

# Then the independent helper implementations:
Task: "Create build-logic/src/main/kotlin/com/ecommerce/build/QualityThresholds.kt"
Task: "Implement build-logic/src/main/kotlin/pact.gradle.kts"
Task: "Implement build-logic/src/main/kotlin/docker-image.gradle.kts and platform/docker/Dockerfile"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: `./gradlew -q verify` is silent and green; failing fixtures show only the failure
5. Demo: a single reproducible command exists for the harness gate

### Incremental Delivery

1. Setup + Foundational, then US1 (MVP: one command)
2. Add US2 (conventions, literal check) then US3 (reference service) - the combination is what feature 004 needs
3. Add US4 (scaffold) then US5 (docs and CI); each story is validated through its Independent Test before moving on

### Parallel Team Strategy

1. One developer finishes Setup + Foundational
2. Then: Developer A takes US1 then US2 (shared root plugin), Developer B writes US3 tests and domain/application code against the conventions as they land, Developer C prepares US5 docs and workflow
3. US4 starts once US3's module layout is final

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps the task to a user story; Setup, Foundational and Polish tasks carry no label
- TestKit tests download dependencies on first run; share `GRADLE_USER_HOME` so later runs and the offline test reuse them
- Resolve "latest stable" versions, base-image digests and action commit SHAs on the day the file is written and record them; never leave a floating tag
- Verify tests fail before implementing; commit after each task or logical group
- Avoid: version literals in any `*.gradle.kts` outside fixtures, Groovy scripts, vague tasks, same-file conflicts between [P] tasks
