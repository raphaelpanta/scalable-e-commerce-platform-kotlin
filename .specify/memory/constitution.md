
# Scalable E-Commerce Platform Constitution

## Core Principles

### I. Kotlin-Idiomatic Monorepo (Gradle Kotlin DSL)

The platform is a single Gradle multimodule monorepo. Every build script MUST be written in the
Gradle Kotlin DSL (`*.gradle.kts`); Groovy DSL is forbidden. Shared build logic MUST live in a
version catalog (`gradle/libs.versions.toml`) and in convention plugins under `build-logic/`;
modules MUST NOT duplicate plugin, dependency or compiler configuration.

Production and test code MUST be idiomatic Kotlin:

- Model data with `data class`, `@JvmInline value class`, sealed hierarchies and enums; never
  with Java-style getters/setters, builders or `Optional`.
- Honour null-safety: `!!` is forbidden outside tests; prefer `?.`, `?:`, `require`/`check`
  and sealed results.
- Prefer extension functions, scope functions, expression bodies and named/default arguments
  over utility classes and overloads.
- Prefer Kotlin-first libraries (kotlinx-coroutines, kotlinx-serialization, Kotest, MockK,
  Arrow where justified) over Java-centric equivalents when an idiomatic option exists.
- ktlint and detekt MUST pass on every module; warnings are treated as errors in CI.

Rationale: one idiom across the monorepo keeps review cost low, lets convention plugins encode
decisions once, and avoids the drift that mixed Groovy/Kotlin builds and Java-style Kotlin create.

### II. Clean / Hexagonal Architecture with Domain-Driven Design

Each service is a bounded context and MUST be structured as concentric layers with the dependency
rule pointing inward:

- `domain`: pure Kotlin. Entities, aggregates, value objects, domain events and domain services.
  Zero framework, persistence, HTTP or messaging dependencies.
- `application`: use cases orchestrating the domain through **ports** (Kotlin interfaces).
  No Spring annotations beyond what is strictly needed for wiring.
- `adapters` / `infrastructure`: inbound adapters (HTTP, messaging consumers, schedulers) and
  outbound adapters (databases, brokers, external APIs) implementing the ports. Spring Boot,
  drivers and clients live here only.

DDD rules:

- Every domain concept with an invariant (money, email, SKU, quantity, order status, identifiers)
  MUST be a value object validated at construction; primitives MUST NOT cross a domain boundary.
- Aggregates own their invariants and are the only transactional consistency boundary; cross-
  aggregate changes happen through domain events, never through shared mutable state.
- Code, tests, Gherkin features and API names MUST use the bounded context's ubiquitous language.
- Module boundaries MUST be enforced by Gradle module separation (one Gradle module per layer or
  an equivalent ArchUnit/Konsist test), not by convention alone.

Rationale: isolating the domain makes business rules testable without infrastructure, lets
adapters be replaced, and keeps microservice boundaries aligned with bounded contexts.

### III. Security by Design (NON-NEGOTIABLE)

Security is a design input, not a review afterthought.

- Every feature spec MUST record its threat model: assets, actors, abuse cases and trust
  boundaries. Every plan MUST include a Constitution Check item for OWASP Top 10 and OWASP API
  Security Top 10 applicability.
- All input MUST be validated at the boundary by constructing value objects; invalid input is
  rejected before reaching application code.
- Authentication and authorization are deny-by-default on every endpoint, message consumer and
  scheduled job. Authorization MUST be enforced in the application layer (not only at the
  gateway) and MUST be covered by tests.
- Secrets, tokens and credentials MUST NOT appear in source, configuration committed to VCS,
  logs or test fixtures. They are injected at runtime from a secret store or environment.
- Persistence access MUST use parameterised queries or typed repositories; string-built queries
  are forbidden.
- Money-moving or state-changing operations exposed over the network MUST be idempotent
  (idempotency keys) and rate limited.
- Personal data MUST be minimised, classified in the spec, encrypted at rest where sensitive and
  never written to logs or traces.
- Dependencies MUST be scanned for known vulnerabilities in CI; a critical finding blocks merge.
- Security headers, CORS allow-lists, CSRF protection for cookie sessions and TLS everywhere are
  mandatory defaults.

Rationale: an e-commerce platform handles identities, payments and personal data; retrofitting
security after a breach is far costlier than designing it in.

### IV. Functional & Non-Blocking First

Code MUST favour a functional style and non-blocking execution.

- Immutability by default: `val`, read-only collections and copy-on-write (`copy`). Mutable state
  is confined to adapters and clearly justified.
- Domain and application code MUST be composed of pure functions where feasible. Side effects are
  pushed to adapter edges behind ports.
- Expected failures in domain and application layers MUST be modelled as values (sealed result
  hierarchies or `Either`-style types). Exceptions are reserved for programming errors and for
  translation at adapter boundaries.
- All I/O MUST be exposed as `suspend` functions; streams MUST use `Flow`. Spring WebFlux with
  coroutine support is the default web stack. Non-blocking drivers and clients (R2DBC, reactive
  Redis, reactive Kafka, WebClient) MUST be used whenever one exists.
- Where only a blocking driver exists, the call MUST be isolated behind a port and dispatched on a
  bounded `Dispatchers.IO` (or dedicated) context with a documented justification.
- `runBlocking` and `GlobalScope` are forbidden in production code; structured concurrency and
  cancellation propagation are required.

Rationale: pure, immutable code is simpler to property-test and reason about; non-blocking I/O
keeps services scalable under e-commerce traffic spikes without thread exhaustion.

### V. Layered Test Contract (NON-NEGOTIABLE)

Every feature MUST ship with the test layers that apply to it. A pull request missing an
applicable layer is rejected.

- **Unit (property-based by default)**: domain and application logic is tested with Kotest
  property testing. Fixtures MUST be produced by generators (`Arb`/`Gen`), not hand-maintained
  object mothers. Example-based tests are allowed only for documented, named edge cases.
- **Integration**: adapters are tested against real or stubbed collaborators, never against mocks
  of the adapter itself. External HTTP dependencies use stub libraries (WireMock or MockServer);
  databases, brokers and caches use Testcontainers. Spring context slices are preferred over
  full-context boots.
- **Contract (Pact)**: every service-to-service and frontend-to-API edge MUST have a consumer-
  driven Pact contract. Provider verification runs in CI and a breaking change to a published
  contract fails the build.
- **Acceptance / E2E (Cucumber)**: acceptance criteria from the spec MUST be expressed as Gherkin
  scenarios in the ubiquitous language of the business. Steps MUST describe behaviour and
  outcomes; they MUST NOT reference endpoints, HTTP verbs, tables, classes, selectors or any other
  implementation detail. Step definitions, not features, bind to the system.
- Tests are written or approved before, or together with, the implementation they cover.
  Red-green-refactor is the expected cadence.
- Mocks (MockK) are permitted only at adapter edges and for ports in application-layer tests.
- Test effectiveness is measured by mutation score (Principle VIII), not by line coverage.
  Coverage percentages MUST NOT be used as a merge criterion on their own.

Rationale: property tests find the cases humans miss, Testcontainers and stubs catch real
integration faults, Pact prevents distributed breakage, and behaviour-level Cucumber keeps the
acceptance suite stable across refactors.

### VI. Microservice Boundaries & Contracts

- One bounded context equals one deployable service with its own datastore. Databases are never
  shared between services; cross-service reads go through APIs or replicated events.
- Inter-service communication uses versioned HTTP/gRPC APIs for queries and asynchronous events
  for state propagation. Synchronous chains deeper than one hop require justification.
- API and event schema changes MUST be backward compatible (additive) or introduced under a new
  version; consumers are migrated before an old version is removed.
- Every service MUST emit structured JSON logs with correlation/trace IDs, expose health, metrics
  and traces, and define SLOs in its spec.
- Shared code between services is limited to small, versioned libraries without domain logic.

Rationale: strict ownership and explicit contracts are what allow services to be built, tested
and deployed independently.

### VII. TypeScript + React Frontend

- TypeScript MUST run in `strict` mode; `any` and unchecked casts are forbidden.
- UI is built from functional React components and hooks; business logic lives in typed modules
  outside components.
- API clients MUST be typed from the contract (OpenAPI/Pact-derived types); hand-written response
  types are forbidden.
- The test contract of Principle V applies: property-based unit tests (fast-check), component
  tests (Testing Library), Pact consumer contracts for every backend edge, and Cucumber-style
  acceptance scenarios (Cucumber.js with Playwright) written as user behaviour.
- ESLint and Prettier MUST pass; warnings are errors in CI.
- Frontend security rules: no `dangerouslySetInnerHTML` without sanitisation, CSP-compatible
  bundles, authentication tokens never stored in `localStorage`, and all user input encoded.

Rationale: the frontend is a first-class consumer of service contracts and must meet the same
quality and security bar as the services it talks to.

### VIII. Token-Efficient, Hook-Driven Harness Engineering

Agent-assisted development MUST be cheap in tokens and safe by construction.

- Subagents are the default for exploration, bulk edits, test runs and reviews. Work is sized to
  the agent: small or fast models for search, lint and formatting; stronger models for design,
  security review and complex debugging. The same search MUST NOT be run twice.
- CLI output MUST be filtered through token-saving wrappers (`rtk`, `tokensave`) and summarised;
  whole files or logs are loaded into context only when the summary is insufficient.
- Gradle MUST run quiet by default: `--quiet` / `org.gradle.logging.level=quiet`,
  `org.gradle.console=plain`, test logging limited to `FAILED` events with
  `exceptionFormat = FULL` and `showStandardStreams = false`. Output is elevated only when a
  build error or test failure occurs. npm/pnpm scripts follow the same rule (`--silent`).
- Hooks enforce quality automatically: on every file edit, lint and the tests for that file
  MUST run; at the end of a task, the full `check` task (ktlint, detekt, unit, integration, Pact
  verification, Cucumber) plus frontend `lint`/`test` MUST run. A failing hook blocks completion.
- Mutation testing is part of the harness: Pitest (with the Kotlin plugin) for JVM modules and
  Stryker for TypeScript packages MUST run against `domain` and `application` code, at minimum,
  in the end-of-task hook and in CI. Each module declares a mutation-score threshold of at least
  80% that MUST NOT decrease; surviving mutants on changed lines MUST be killed or explicitly
  justified in the pull request. Mutation runs are incremental (changed classes/files only) in
  hooks and full in CI, and their output follows the quiet-logging rule above.
- Agents MUST read this constitution and `CLAUDE.md` before planning and MUST record deviations
  in the plan's Complexity Tracking table.

Rationale: hooks make quality independent of agent diligence, mutation testing proves the tests
actually detect faults rather than merely executing lines, and token discipline keeps iteration
fast and affordable across a large monorepo.

## Technology Stack & Constraints

Backend (per service):

- Kotlin (latest stable), JVM LTS, Spring Boot on the latest GA major line (4.1 at the time of this
  amendment; versions pinned in `gradle/libs.versions.toml`) with WebFlux and coroutine support.
- Gradle with Kotlin DSL, version catalog and `build-logic/` convention plugins.
- kotlinx-coroutines, kotlinx-serialization (or Jackson Kotlin module when required by Spring).
- Persistence: R2DBC with Flyway or Liquibase migrations; reactive Redis; reactive Kafka client.
- Testing: Kotest (incl. property testing), MockK, Testcontainers, WireMock/MockServer,
  Pact JVM, Cucumber JVM, ArchUnit or Konsist for architecture rules.
- Quality: ktlint, detekt, Pitest (mutation testing), dependency vulnerability scanning,
  SBOM generation.

Frontend:

- TypeScript (strict), React, Vite, Vitest + fast-check, Testing Library, Pact JS,
  Cucumber.js + Playwright, Stryker (mutation testing), ESLint, Prettier.

Hard constraints:

- No Groovy Gradle scripts.
- No blocking JDBC or blocking HTTP clients on request/event paths.
- No shared databases between services.
- No framework types in `domain` modules.
- No Java-centric library where an idiomatic Kotlin option exists.
- Production logging is structured JSON; no PII or secrets in any log level.

## Development Workflow & Quality Gates

Spec Kit flow: `/speckit-specify` -> `/speckit-clarify` -> `/speckit-plan` (Constitution Check
gate) -> `/speckit-tasks` -> `/speckit-implement`. Specs MUST include acceptance criteria in
behaviour language (source for Cucumber features) and the threat model required by Principle III.

Quality gates:

1. **Per-file hook gate**: every edit triggers lint (ktlint/detekt or ESLint/Prettier) and the
   tests associated with the changed file. Failures must be fixed before moving on.
2. **End-of-task hook gate**: the complete `gradle check` (quiet, failures only), frontend
   `lint`/`test`, and incremental mutation testing (Pitest/Stryker on changed code) run; the
   task is not done until green and the mutation threshold holds.
3. **Pull request gate**: all test layers of Principle V green, Pact provider verification
   passed, full mutation run at or above each module's threshold with no unjustified surviving
   mutants on changed lines, security checklist ticked, architecture tests passed, Constitution
   Check in `plan.md` passed, and any deviation justified in Complexity Tracking.

Subagent policy: fan out when work is parallelisable or exploratory; keep the orchestrating agent
focused on decisions; pass summaries, not raw output, between agents.

Definition of Done: spec acceptance scenarios pass as Cucumber features; property, integration
and contract tests exist and pass; mutation score at or above threshold; lint clean; threat model
addressed; observability in place;
documentation (README of the module, ADR if an architectural decision was made) updated.

## Governance

This constitution supersedes all other practices, templates and personal preferences. Where a
template, skill or `CLAUDE.md` conflicts with it, the constitution wins and the conflicting
document MUST be amended.

Amendment procedure: propose the change in a pull request that edits this file, includes a Sync
Impact Report, states the rationale and lists migration steps for affected code, templates and
hooks. Amendments are approved by the maintainers and take effect on merge.

Versioning policy (semantic):

- MAJOR: a principle is removed, redefined or made backward incompatible.
- MINOR: a principle or section is added or materially expanded.
- PATCH: clarifications, wording and non-semantic refinements.

Compliance review: every `plan.md` MUST pass the Constitution Check before Phase 0 research and
again after Phase 1 design. Every pull request review MUST verify compliance with Principles I-VIII.
Complexity or deviations MUST be justified in the plan's Complexity Tracking table; unjustified
deviations are rejected. Runtime development guidance for agents lives in `CLAUDE.md`.

**Version**: 1.1.1 | **Ratified**: 2026-10-02 | **Last Amended**: 2026-10-02
