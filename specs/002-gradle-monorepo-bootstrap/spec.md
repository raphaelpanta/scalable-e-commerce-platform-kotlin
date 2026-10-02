# Feature Specification: Bootstrap Gradle Kotlin DSL Multimodule Monorepo

**Feature Branch**: `002-gradle-monorepo-bootstrap`

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description: "bootstrap Gradle Kotlin DSL multimodule monorepo"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Build and verify the whole repository with one command (Priority: P1)

A developer or agent clones the repository on a clean machine and, with a single documented
command and no manual tool installation beyond a supported runtime, builds every module and runs
every quality check. Output is silent on success and shows only failures. The same command is
what the quality gates of the harness feature and the central pull-request gate run.

**Why this priority**: Nothing else in the project can be developed, tested or gated until the
repository builds reproducibly from a clean checkout. This is the foundation every other feature
stands on.

**Independent Test**: On a machine with only the supported runtime installed, clone the
repository, run the documented verify command, and confirm it completes green with no more than a
handful of output lines; introduce a failing test and confirm the same command fails with only the
failure shown.

**Acceptance Scenarios**:

1. **Given** a fresh clone and a supported runtime, **When** the developer runs the documented
   verify command, **Then** all modules build, all checks pass, and the output contains no
   lifecycle or progress noise.
2. **Given** a module with a failing test, **When** the verify command runs, **Then** it exits
   non-zero and the output shows the failing test with its full assertion message and nothing
   about passing tests.
3. **Given** the verify command has run once, **When** it is run again with no changes, **Then**
   it completes in a fraction of the first run's time by reusing prior results.
4. **Given** the developer is offline, **When** they run the build after a prior online build,
   **Then** it still succeeds using previously fetched dependencies.

---

### User Story 2 - Shared build conventions declared once (Priority: P1)

Every dependency version, compiler setting, style rule, test configuration and quality threshold
is declared in exactly one place. A module opts into a named convention and inherits the full,
consistent setup. Changing a shared setting in its single location changes it for every module.

**Why this priority**: Without this, each new service drifts into its own build configuration,
review cost grows with every module, and constitution rules such as quiet logging and style
checks cannot be enforced uniformly.

**Independent Test**: Change a shared dependency version in its single declaration, rebuild, and
confirm every module picked up the new version; grep the repository and confirm no module
declares that version itself.

**Acceptance Scenarios**:

1. **Given** a dependency used by several modules, **When** its version is changed in the shared
   catalogue, **Then** every module uses the new version without any module-level edit.
2. **Given** a new module that applies the service convention, **When** it is built, **Then** it
   automatically has style checks, the four test layers, mutation testing, the compiler settings
   and quiet logging configured, with no module-level configuration for any of them.
3. **Given** a module build definition that repeats a version already in the shared catalogue,
   **When** the build runs, **Then** the build fails with a message pointing to the catalogue.
4. **Given** the constitution's style rules, **When** any module is built, **Then** a style
   violation fails the build in that module.

---

### User Story 3 - Reference service demonstrating the mandated architecture (Priority: P2)

The repository contains one reference service, structured in the constitution's layers (domain,
application, adapters), with the four test layers in place and passing, architecture rules
enforced by the build, and mutation testing configured. It carries no business logic beyond what
is needed to prove the wiring end to end, and it serves as the living template for every real
service.

**Why this priority**: Rules that exist only in the constitution get violated. A compiling,
tested, enforced example makes the rules concrete and gives developers and agents something to
copy.

**Independent Test**: Add a framework import to the reference service's domain layer and confirm
the build fails with an architecture-rule violation naming the layer and the forbidden dependency.

**Acceptance Scenarios**:

1. **Given** the reference service, **When** the verify command runs, **Then** its unit,
   integration, contract and acceptance test layers each run as distinct, individually runnable
   steps and all pass.
2. **Given** the reference service, **When** a domain-layer file imports from the application or
   adapter layer or from any framework, **Then** the build fails with an architecture-rule
   violation that names the offending file and rule.
3. **Given** the reference service, **When** mutation testing runs, **Then** it reports a score
   for the domain and application layers and fails the build if the score is below the
   constitution's threshold.
4. **Given** the reference service, **When** it is started locally, **Then** it answers a health
   request and exposes the structured logs and metrics the constitution requires.

---

### User Story 4 - Add a new service in minutes (Priority: P2)

A developer can create a new bounded-context service following a documented, repeatable procedure
that produces the same structure as the reference service. The new service is part of the whole-
repository build immediately and passes the verify command before any business code is written.

**Why this priority**: The platform is a microservice architecture; adding services must be cheap
and uniform, or teams will take shortcuts that erode the architecture.

**Independent Test**: Follow the documented procedure to add a service named for a new bounded
context, run the verify command, and confirm it passes with the new service included, within the
time budget stated in the success criteria.

**Acceptance Scenarios**:

1. **Given** the documented procedure, **When** a developer adds a service, **Then** the only
   changes required are registering the service and naming the convention it applies.
2. **Given** a newly added service, **When** the verify command runs, **Then** the new service is
   built and checked together with every other module.
3. **Given** a newly added service, **When** its structure is compared with the reference
   service, **Then** the layer and test-layer layout is identical.

---

### User Story 5 - Build documentation and CI readiness (Priority: P3)

The repository explains how the build is organised, how to run each kind of check, how to add a
module or a dependency, and how the verify command maps to the quality gates. A continuous-
integration configuration runs the same verify command on every pull request.

**Why this priority**: The build is shared infrastructure; it must be understandable by everyone
and enforced centrally, but it only has value after the build itself exists.

**Independent Test**: A developer unfamiliar with the project follows the documentation to add a
dependency to one module and run only that module's unit tests, succeeding without asking anyone.

**Acceptance Scenarios**:

1. **Given** the build documentation, **When** a developer looks up how to run a single test
   layer for a single module, **Then** they find a copy-pasteable command that works.
2. **Given** a pull request, **When** continuous integration runs, **Then** it executes the same
   verify command as the local gate and reports the consolidated status on the pull request.

---

### Edge Cases

- A module omits the convention it should apply: the whole-repository build fails with a message
  identifying the module, rather than silently building it without checks.
- Two modules need different versions of the same dependency: the build refuses the conflict
  with a message pointing to the shared catalogue, so divergence is a conscious decision.
- The build runs on a machine whose runtime version differs from the declared one: the build
  fails fast with a message stating the required version, instead of failing obscurely later.
- A module has no tests in a given test layer: that layer's step reports "no tests" and passes;
  mutation testing on a module with no tests is skipped, not failed.
- A nested module path (service inside a group directory) is registered: it is built and
  addressed by its full path consistently.
- The repository is built in continuous integration without a warm cache: it still succeeds
  within the stated time budget.
- Output is captured by a non-interactive consumer such as an agent or a log collector: no
  colour codes, progress bars or cursor movements appear.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The repository MUST build and verify all modules with a single documented command
  that requires only a supported runtime to be pre-installed; the build tool itself is bootstrapped
  by the repository.
- **FR-002**: The build tool version and runtime version MUST be pinned in the repository and
  enforced; a mismatch MUST fail fast with an explanatory message.
- **FR-003**: Build output MUST be quiet by default: no lifecycle, progress or colour output on
  success; failures MUST show the failing step and, for tests, the full assertion message.
- **FR-004**: Every dependency version MUST be declared once in a shared catalogue; module-level
  version literals MUST fail the build.
- **FR-005**: Compiler settings, style rules, test configuration, quiet-logging policy and quality
  thresholds MUST be declared once as named conventions that modules apply by name.
- **FR-006**: Applying the service convention to a module MUST give it, without further
  configuration: style checks, four separately runnable test layers (unit, integration, contract,
  acceptance), mutation testing on its domain and application code, and the compiler settings.
- **FR-007**: Style violations MUST fail the build of the module in which they occur.
- **FR-008**: The repository MUST contain a reference service structured in the constitution's
  layers, with all four test layers present and passing and a health endpoint, and with no
  business logic.
- **FR-009**: The build MUST enforce the architecture rules of the constitution: domain code
  MUST NOT depend on application, adapter or framework code; application code MUST NOT depend on
  adapter code. Violations MUST fail the build naming the file and the rule.
- **FR-010**: Mutation testing MUST fail the build when a module's score is below its declared
  threshold, defaulting to the constitution's minimum, and MUST be skipped for modules without
  tests.
- **FR-011**: The build MUST reuse prior results so that an unchanged repository verifies in a
  fraction of the first run's time, and MUST succeed offline after a prior online build.
- **FR-012**: Adding a new service MUST require only registering it and naming the convention it
  applies; its structure MUST match the reference service.
- **FR-013**: A module that neither applies a convention nor is explicitly exempted MUST fail
  the whole-repository build with a message identifying it.
- **FR-014**: Each test layer MUST be runnable on its own for a single module and for the whole
  repository.
- **FR-015**: The repository MUST reserve a location for the frontend application and include its
  checks in the whole-repository verify command once that application exists.
- **FR-016**: The repository MUST include build documentation covering: layout, the verify
  command, running each test layer, adding a module, adding a dependency, and how the verify
  command maps to the quality gates.
- **FR-017**: A continuous-integration configuration MUST run the verify command on every pull
  request and report a consolidated status.
- **FR-018**: Shared build logic MUST itself be covered by tests so that changes to conventions
  are verified before they affect every module.

### Key Entities

- **Module**: A buildable unit with a path, an applied convention and a layer role (domain,
  application, adapter, or shared build logic).
- **Convention**: A named, shared set of build rules a module applies; the single source of
  truth for compiler, style, test, logging and threshold settings.
- **Dependency Catalogue Entry**: A named dependency with its single declared version.
- **Test Layer**: One of unit, integration, contract or acceptance; each has its own source
  location and runnable step per module.
- **Architecture Rule**: A constraint on which layers may depend on which, enforced at build time.
- **Reference Service**: The template service proving the layers, test layers, rules and health
  wiring end to end.
- **Verify Command**: The single entry point that builds and checks the whole repository and that
  all quality gates invoke.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From a fresh clone on a machine with only the supported runtime, the verify command
  completes green in under 10 minutes without a warm cache and under 2 minutes when nothing has
  changed.
- **SC-002**: A passing verify run prints at most 5 lines; a failing run prints the failing step
  and assertion and nothing about passing steps.
- **SC-003**: A repository-wide search finds zero dependency version literals outside the shared
  catalogue.
- **SC-004**: A new service can be added and pass the verify command in under 15 minutes by a
  developer following the documentation, touching no more than two build files.
- **SC-005**: A seeded architecture violation in the reference service's domain layer is caught by
  the build in 100% of attempts, with the offending file named.
- **SC-006**: Each of the four test layers can be run alone for one module with a single command
  found in the documentation, and reports its results separately.
- **SC-007**: The reference service meets the constitution's mutation threshold, and lowering a
  threshold below the constitution's minimum fails the build.
- **SC-008**: Every pull request shows the verify command's consolidated status within 15 minutes
  of being opened.

## Assumptions

- The build tool and build-script language are fixed by the constitution and by the feature
  request; this spec treats them as given scope, not as an open implementation choice.
- One reference service is scaffolded, named for a real but simple bounded context (for example a
  catalogue of products), and carries only health wiring and example tests, no business rules.
  Real bounded contexts are added by later features using the procedure in this spec.
- The frontend application is not scaffolded by this feature; only its location is reserved and
  the hook for including its checks is prepared. A separate feature creates the application.
- The constitution's thresholds and defaults apply where this spec is silent: mutation threshold
  of 80% that may not decrease, the constitution's style rules, structured logs with correlation
  identifiers, and health and metrics exposure per service.
- The "supported runtime" is the long-term-support runtime version the plan selects; developers
  install it themselves or via a documented version manager.
- A hosted source-control service with pull requests and a continuous-integration runner is
  available; the provider is chosen at planning time.
- The quality gates defined in the harness feature (specs/001) call the verify command; this spec
  does not redefine the gates, only provides the command they run.
- Integration, contract and acceptance tests in the reference service use the stub, container
  and contract approaches the constitution mandates; the exact tools are planning decisions.
