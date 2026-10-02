# Quickstart: Gradle Monorepo Bootstrap

**Feature**: 002-gradle-monorepo-bootstrap | **Audience**: developers and agents validating the build

All commands run from the repository root. Prerequisites: JDK 25 (`sdk env` reads `.sdkmanrc`, or
any installer that honours `.java-version`) and a Docker-API-compatible engine (needed only by the
integration, contract and acceptance layers and by `dockerImage`). Nothing else is installed; the
Gradle 9.8.0 distribution is downloaded by `./gradlew` and verified against its pinned checksum.

Output is silent on success. "Prints nothing" below is the expected result of a green run.

## 1. Fresh clone: verify everything

```bash
git clone <repository-url> && cd scalable-e-commerce-platform-kotlin
time ./gradlew -q verify
```

Expected: exit code 0 and no output (SC-002 allows at most 5 lines). Timing on a machine with 8
cores, an empty Gradle cache and a warm Docker image cache: about 6 to 9 minutes, hard limit 10
minutes (SC-001). The time is dominated by dependency download, TestKit fixture builds in
`build-logic`, Testcontainers start-up and the mutation run.

`verify` runs, in order: toolchain consistency (`checkToolchain`), the version-literal check
(`checkVersionLiterals`), the convention guard, the `build-logic` included build's `check`
(TestKit tests), `check` of every module (ktlint, detekt, unit, integration, contract,
acceptance, architecture rules, Pitest) and the frontend `lint` and `test` once
`frontend/package.json` exists.

## 2. Unchanged re-run and offline rebuild

```bash
time ./gradlew -q verify                  # nothing changed
time ./gradlew -q --offline verify        # no network, after the online run above
```

Expected: both print nothing; the first finishes in well under 2 minutes (target about 20 to 40
seconds, every task `UP-TO-DATE` or `FROM-CACHE`) and the second succeeds using already downloaded
dependencies (SC-001, FR-011).

## 3. Failure output shows only the failure

Add a deliberately wrong assertion to `services/catalog/domain/src/test/kotlin/com/ecommerce/catalog/domain/ServiceNameTest.kt`
(for example `1 shouldBe 2`), then:

```bash
./gradlew -q verify
```

Expected: exit code 1; output names the failing task (`:services:catalog:domain:test`), the test
name and the full assertion message (`expected:<2> but was:<1>`), and nothing about passing tests,
no colour codes and no progress lines (SC-002, AC 1.2). Revert the edit.

## 4. Change a version in the catalogue

```bash
# edit gradle/libs.versions.toml: [versions] kotest = "6.2.4"   (any other published version)
./gradlew -q :services:catalog:application:dependencies --configuration testRuntimeClasspath | grep kotest-assertions
./gradlew -q :services:catalog:domain:dependencies --configuration testRuntimeClasspath | grep kotest-assertions
git diff --stat                           # only gradle/libs.versions.toml changed
./gradlew -q checkVersionLiterals         # prints nothing
```

Expected: both modules resolve the new version with no module script edited (AC 2.1). Then add
`implementation("org.example:lib:1.2.3")` to `services/catalog/application/build.gradle.kts` and run
`./gradlew -q checkVersionLiterals`: it fails with
`services/catalog/application/build.gradle.kts:<line>: version literal 'org.example:lib:1.2.3'; declare it in gradle/libs.versions.toml ...`
(AC 2.3). A repository-wide search for literals,
`grep -rEn '"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][^"]*"' --include='*.gradle.kts' . | grep -v fixtures`,
returns nothing (SC-003). Revert both edits.

## 5. Seeded architecture violation

```bash
# add to services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/ServiceName.kt:
#   import org.springframework.stereotype.Component
./gradlew -q :services:catalog:infrastructure:test
```

Expected: the build fails 100 % of the time, naming the file (SC-005, AC 3.2). Two guards exist and the
first one wins here:

1. The module graph. `kotlin-domain` puts no framework on the domain classpath, so the import does not
   compile: `:services:catalog:domain:compileKotlin` fails with
   `e: .../ServiceName.kt:3:12 Unresolved reference 'springframework'.` before any test runs.
2. The shared Konsist rules (`config/architecture`, run in the infrastructure unit layer). They read the
   sources, so they also catch a forbidden import that would compile, and report
   `Rule 'domain must not import frameworks' violated by .../ServiceName.kt: import org.springframework.stereotype.Component`.
   `ArchitectureRulesSeededViolationTest` seeds exactly this import (and one adapter import in
   `application`) into a temporary service tree and asserts that message on every `verify`.

Also try adding `implementation(project(":services:catalog:application"))` to
`services/catalog/domain/build.gradle.kts`: the build fails at configuration with
`domain must not depend on :services:catalog:application`. Revert both edits.

## 6. Run one test layer for one module

```bash
./gradlew -q :services:catalog:domain:test                       # unit (property tests)
./gradlew -q :services:catalog:infrastructure:integrationTest    # Testcontainers: health, Flyway, metrics
./gradlew -q :services:catalog:infrastructure:contractTest       # Pact consumer + provider verification
./gradlew -q :services:catalog:infrastructure:acceptanceTest     # Cucumber feature
./gradlew -q :services:catalog:domain:pitest                     # mutation testing, threshold 80
./gradlew -q integrationTest                                     # the same layer across the whole repository
```

Expected: each command runs only its layer, prints nothing on success and leaves its own report
under `services/catalog/<module>/build/reports/` (SC-006, FR-014). A layer with no tests reports
nothing and passes; Pitest on a module without tests is skipped. To check SC-007, set
`pitest { mutationThreshold.set(79) }` in `services/catalog/domain/build.gradle.kts` and run
`./gradlew -q :services:catalog:domain:pitest`: the build fails with
`Mutation threshold 79 is below the constitution minimum 80`. Revert.

## 7. Add a service (walkthrough)

```bash
time ( ./gradlew newService -Pname=orders && ./gradlew -q verify )
git status --short
```

What `newService` does: validates the name against `[a-z][a-z0-9]*` (otherwise it fails with
`name must match [a-z][a-z0-9]*`; without `-Pname` with `-Pname=<context> is required`), refuses an
existing service (`already exists`), creates `services/orders/{domain,application,infrastructure}` with the
same directories as the reference service, writes the three one-line module scripts
(`plugins { id("kotlin-domain") }`, `kotlin-application`, `kotlin-service`), the package
`com.ecommerce.orders` with placeholder domain and application types and their unit tests,
`OrdersApplication.kt`, `application.yml`, `V1__baseline.sql`, and one test per infrastructure layer
(integration, Pact consumer and provider, Cucumber) with its own `PostgresContainerConfig`, and appends
`includeService("orders")` below the marker line in `settings.gradle.kts`. Expected: `verify` passes with the
new service built and checked with every other module, in under 15 minutes in total, and the only existing
build file modified is `settings.gradle.kts` (SC-004, FR-012, AC 4.1 to 4.3). Remove the scratch service
with `rm -rf services/orders` and delete its `includeService("orders")` line.

Manual equivalent: create the three directories, the three one-line `build.gradle.kts` files above
and the `includeService("<name>")` line; omit a script's plugin line and `verify` fails with
`Module :services:orders:domain applies no convention` (FR-013).

## 8. Optional: build the image and see the CI status

```bash
./gradlew :services:catalog:infrastructure:dockerImage    # outside verify; needs Docker
```

Open a pull request: the `verify` job of `.github/workflows/verify.yml` runs
`./gradlew -q verify` and reports one consolidated status within 15 minutes (SC-008, FR-017).

## Expected outcomes mapped to success criteria

| Criterion | Where it is demonstrated | Expected outcome | Checked |
|-----------|--------------------------|------------------|---------|
| SC-001 timing | Sections 1 and 2 | Cold under 10 min, unchanged under 2 min, offline run succeeds | [x] 5 min 30 s cold, 16 s unchanged, 15 s offline (2026-10-02) |
| SC-002 output size | Sections 1 and 3 | 0 lines on success (limit 5); failure shows step and assertion only | [x] 0 lines on success; seeded failure shows task, test and assertion |
| SC-003 no version literals | Section 4 | Zero matches outside the catalogue; a literal fails `checkVersionLiterals` | [x] grep empty; literal reported with file and line |
| SC-004 add a service | Section 7 | `newService` + `verify` under 15 min, one existing build file touched | [x] `newService -Pname=sample` + `verify` green, only `settings.gradle.kts` changed |
| SC-005 seeded violation | Section 5 | Build fails 100 % of attempts, offending file and rule named | [x] fails at `domain:compileKotlin` naming the file; Konsist rule covered by its seeded test |
| SC-006 single layer | Section 6 | Each layer runs alone for one module with one documented command | [x] each of the four layers ran alone, silent |
| SC-007 mutation threshold | Section 6 | Reference service at or above 80; threshold 79 fails the build | [x] threshold 79 fails with "below the constitution minimum 80" |
| SC-008 CI status | Section 8 | `verify` check on every pull request within 15 min | [ ] pending: confirmed on the first pull request |
