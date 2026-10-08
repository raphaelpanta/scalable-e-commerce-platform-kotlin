# Tasks: README Overhaul

**Input**: Design documents from `/specs/006-readme-overhaul/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/readme-structure.md, quickstart.md
**Tests**: Included. FR-015 and SC-004 require the README checks in `scripts/tests/test_community_files.sh`, proven
against a deliberately broken copy. The checks are written before the README content they guard.
**Organization**: One README file serves all three stories, so README tasks are sequential (no `[P]`). The
foundational phase builds the checks and the section skeleton, so `./gradlew -q verify` (run by the stop hook at
every turn end) stays green after each phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US3)

## Path Conventions

- Page: `README.md` (repository root). Structure rules: `specs/006-readme-overhaul/contracts/readme-structure.md`.
- Test: `scripts/tests/test_community_files.sh`, helpers from `scripts/tests/lib/assert.sh` (`test_case`,
  `assert_eq`, `assert_contains`, `mk_tmp`, `finish_tests`); runs in `scripts/tests/run-all.sh` and in `verify`
  through `scriptsTest`.
- Facts to cite: `docs/architecture.md` (contexts table section 1, Mermaid diagram), `docs/running-locally.md`,
  `docs/dev-environment.md`, `docs/build.md`, `.java-version`, `gradle/libs.versions.toml`, `frontend/package.json`.
- Shell is Bash 3.2-compatible, quoted patterns, shellcheck-clean. Run `verify` only in the foreground, never two at once.

---

## Phase 1: Setup

- [X] T001 Create branch `006-readme-overhaul` from an up-to-date `main` (`git switch -c 006-readme-overhaul`)
- [X] T002 Run `bash scripts/tests/test_community_files.sh` and confirm it passes before any change (baseline for
  SC-004; the README case is at lines 24–33)

---

## Phase 2: Foundational (checks and skeleton; blocks all stories)

**Purpose**: The three FR-015 checks exist and are proven to fail on broken input, and `README.md` already has the
contract's section order so every later task only fills sections in.

- [X] T003 Add `readme_broken_links FILE` to `scripts/tests/test_community_files.sh`: extract every Markdown link
  target `](...)` (including badge links), skip `http://`, `https://`, `mailto:` and targets starting with `#`, drop
  any `#fragment`, resolve relative to `$PROJECT_ROOT`, print `broken link: <target>` for each missing path
- [X] T004 Add `readme_section_order FILE` to the same file: read `^## ` headings and check the nine headings of the
  contract (`Contents`, `Overview`, `Quick start`, `Architecture`, `Repository layout`, `Build and test`,
  `Documentation`, `Contributing`, `License`) appear in that order (other headings allowed between); print
  `section missing or out of order: <heading>` for the first failing one
- [X] T005 Add `readme_badge_drift FILE` to the same file: for each label in the contract's version-badge table
  (`JDK` ← `.java-version`; `Kotlin` ← `kotlin = "<v>"` and `Spring%20Boot` ← `spring-boot = "<v>"` in
  `gradle/libs.versions.toml`; `Node` ← first major number of `engines.node`, `React` ← `dependencies.react`,
  `TypeScript` ← `devDependencies.typescript` in `frontend/package.json`, read with `jq`), extract the value from
  `img.shields.io/badge/<Label>-<value>-` in FILE and print `badge <Label>: README <x>, source <y>` when it differs
  or is missing
- [X] T006 Add test case "README checks catch a broken copy" to the same file: in `mk_tmp`, write a minimal fixture
  README holding the nine headings in order, a link to `LICENSE` and the six version badges with the source values;
  assert all three functions print nothing; then, on separate copies, add a link to `docs/does-not-exist.md`, swap
  `## Overview` and `## Quick start`, and change the Kotlin badge value; assert each matching function prints its
  violation line (depends on T003–T005)
- [X] T007 Restructure `README.md` into the contract skeleton without losing content: keep the title
  `# Scalable E-Commerce Platform (Kotlin)`, add a one-line pitch, keep the `license-MIT` badge linking `LICENSE`,
  then the nine `##` headings in contract order with a `## Contents` list linking each (`- [Overview](#overview)`
  …); move existing paragraphs into the matching sections; correct stale facts per research section 5 ("Spring Boot
  3" → Spring Boot 4, `./gradlew check` → `./gradlew -q verify`, remove "once it is present"); keep
  `.specify/memory/constitution.md`, `specs/`, `scripts/bootstrap-repo.sh` and the words architecture, build, verify
  (FR-011, FR-012)
- [X] T008 Add test case "README links resolve, sections are ordered, badges match their sources" to
  `scripts/tests/test_community_files.sh`: assert `readme_broken_links README.md` and `readme_section_order README.md`
  print nothing (the badge-drift assertion joins this case in T014, once the badges exist); run the file and
  confirm every case passes (depends on T006, T007)

**Checkpoint**: `bash scripts/tests/test_community_files.sh` passes; README has the final section order.

---

## Phase 3: User Story 1 - First-time visitor runs the platform (Priority: P1) 🎯 MVP

**Goal**: From the README alone a newcomer installs prerequisites, starts the platform, opens it and stops it.

**Independent Test**: On a prepared machine, follow only `## Quick start` to `http://localhost:8080` with at most
three commands (SC-001); the quick start starts within the first 40 rendered lines (SC-002).

- [X] T009 [US1] Write `## Overview` in `README.md`: 3–5 sentences (what the platform is, the six services plus
  gateway and storefront, spec-first with Spec Kit linking `.specify/memory/constitution.md` and `specs/`); keep it
  short so `## Quick start` begins within the first 40 lines
- [X] T010 [US1] Add `### Prerequisites` under `## Quick start` in `README.md`: table with columns Tool, Version,
  Why — JDK `25`, Docker or Podman with Compose v2 (about 10 GiB for the engine), Node `24` and npm, `gitleaks`,
  `curl`, `jq`, `openssl`, `git`; one sentence that `scripts/dev-env.sh init` checks them, prints the exact fix and
  exits 3 without changing anything, and that `--install` installs missing tools (source:
  `docs/running-locally.md` "Bootstrap", `docs/dev-environment.md`)
- [X] T011 [US1] Add `### Start` in `README.md`: `git clone https://github.com/raphaelpanta/scalable-e-commerce-platform-kotlin.git`
  then `cd` and `scripts/dev-env.sh init --start`, each in its own ` ```bash ` block followed by its expected
  outcome (first build takes a few minutes; ends by printing the addresses); a `> [!WARNING]` alert on giving the
  container engine about 10 GiB, and a `> [!NOTE]` for Podman (`BUILDAH_FORMAT=docker`, set by the script) linking
  `docs/running-locally.md` (FR-005, FR-010)
- [X] T012 [US1] Add `### What you get` in `README.md`: table Address, What — `http://localhost:8080` storefront and
  API (only public entry point), `http://localhost:3000` Grafana dashboards, `http://localhost:8025` Mailpit test
  mailbox (FR-006)
- [X] T013 [US1] Add `### Status and stop` in `README.md`: `scripts/dev-env.sh status` and `scripts/dev-env.sh down`
  with one-line effects, then a `<details><summary>Manual start (without the script)</summary>` holding a 3–5 line
  summary and a link to `docs/running-locally.md` (FR-010, FR-013)

**Checkpoint**: US1 is usable on its own; community-files test still passes.

---

## Phase 4: User Story 2 - Evaluator grasps the system at a glance (Priority: P2)

**Goal**: Badges, two diagrams and a services table let a reviewer understand scope and health in two minutes.

**Independent Test**: Header shows licence, exactly one build badge and the version badges; overview diagram renders;
the services table names each service with a responsibility and its own build badge (SC-005).

- [X] T014 [US2] Add header badges under the pitch in `README.md`: one build badge
  `[![verify](https://github.com/raphaelpanta/scalable-e-commerce-platform-kotlin/actions/workflows/verify.yml/badge.svg)](https://github.com/raphaelpanta/scalable-e-commerce-platform-kotlin/actions/workflows/verify.yml)`,
  the existing licence badge, and shields.io static badges `JDK-25`, `Kotlin-2.3.21`, `Spring%20Boot-4.1.1`,
  `Node-24`, `React-19.3.0`, `TypeScript-5.9.3` (values copied from the sources at the time of writing; FR-003);
  then add `readme_badge_drift README.md` prints nothing to the T008 test case in
  `scripts/tests/test_community_files.sh` and run the file
- [X] T015 [US2] Add the overview diagram to `## Architecture` in `README.md`: one ` ```mermaid ` `flowchart LR`,
  simplified from `docs/architecture.md` section 1 — browser → gateway → storefront and the six services; synchronous
  calls cart→catalog, order→cart/catalog/payment/identity; dotted event edges to and from `Kafka`; one database
  node per service with a database; no colour styling (dark-mode safe); one sentence legend (solid = HTTP,
  dotted = events) and a link to `docs/architecture.md` (FR-007)
- [X] T016 [US2] Add the services table to `## Architecture` in `README.md`: columns Service, Responsibility,
  Build — rows identity, catalog, cart, order, payment, notification, gateway, storefront, responsibilities
  condensed to one line from `docs/architecture.md` section 1 "Owns", build badge
  `actions/workflows/<name>.yml/badge.svg` linking that workflow (FR-008)
- [X] T017 [US2] Add `<details><summary>Purchase flow</summary>` to `## Architecture` in `README.md` with one
  ` ```mermaid ` `sequenceDiagram`: shopper → gateway → order (checkout) → cart (read) → catalog (reserve stock) →
  payment (charge) → `PaymentApproved` event → order `OrderPaid` event → catalog commit, cart clear, notification
  email; base it on `docs/architecture.md` and ADRs 0001–0003 under `docs/adr/` (FR-007)

**Checkpoint**: exactly two Mermaid blocks, the second inside `<details>`; test passes.

---

## Phase 5: User Story 3 - Contributor finds the right deeper document (Priority: P3)

**Goal**: Accurate build commands and a documentation map route contributors in one click.

**Independent Test**: From the README, reach the per-layer test commands, `docs/ci-cd.md` and `CONTRIBUTING.md`
in one click each (SC-006).

- [X] T018 [US3] Write `## Repository layout` in `README.md`: a short ` ```text ` tree of top-level directories
  (`services/`, `libs/`, `frontend/`, `platform/`, `contracts/`, `acceptance/`, `build-logic/`, `gradle/`,
  `scripts/`, `docs/`, `specs/`) with one comment each
- [X] T019 [US3] Write `## Build and test` in `README.md`: `./gradlew -q verify` (whole gate, silent on success),
  one layer of one module (`./gradlew -q :services:<name>:infrastructure:integrationTest`, `:domain:test`),
  `./gradlew -q contractTest contractVerify`, `./gradlew newService -Pname=<context>`, and the script tooling
  (`scripts/tests/run-all.sh`, `scripts/lint.sh`); a `> [!TIP]` that integration tests need the container engine;
  link `docs/build.md` and `docs/ci-cd.md` (FR-009; commands as in `CLAUDE.md` "Build")
- [X] T020 [US3] Write `## Documentation` in `README.md`: table Document, Read it when — `docs/build.md`,
  `docs/dev-environment.md`, `docs/running-locally.md`, `docs/architecture.md`, `docs/ci-cd.md`,
  `docs/storefront.md`, `docs/gateway.md`, `contracts/README.md`, `CONTRIBUTING.md`, `SECURITY.md` (data-model
  "Documentation map entry")
- [X] T021 [US3] Write `## Contributing` and `## License` in `README.md`: keep the existing contributing paragraph
  (branch, PR checklist, protected `main`, squash merge, gitleaks hook, code of conduct, private vulnerability
  reporting via `SECURITY.md`); move "How this repository was published" into
  `<details><summary>How this repository was published</summary>` keeping the `scripts/bootstrap-repo.sh` commands
  and flags; licence line linking `LICENSE`

**Checkpoint**: all stories complete; every link in the page resolves.

---

## Phase 6: Polish & Cross-Cutting

- [X] T022 Review `README.md` against the contract: ≤ ~250 source lines (`wc -l`), `## Quick start` within the first
  40 lines, every `## Contents` anchor matches a heading slug, exactly two Mermaid blocks, header has exactly one
  `badge.svg`; trim duplicated detail into links (FR-013)
- [X] T023 [P] Run `scripts/lint.sh` and fix any shellcheck finding in `scripts/tests/test_community_files.sh`
- [X] T024 Run `scripts/tests/run-all.sh` then `./gradlew -q verify` in the foreground (exit 0, no output; SC-003,
  SC-004)
- [ ] T025 After pushing the branch, do the rendering review in `specs/006-readme-overhaul/quickstart.md` section 3
  on github.com (light, dark, phone width) and record the result in the pull request description

---

## Dependencies & Execution Order

- Setup (T001–T002) → Foundational (T003–T008) → US1 (T009–T013) → US2 (T014–T017) → US3 (T018–T021) → Polish.
- T003, T004, T005 touch the same file, so they are sequential; T006 needs all three; T008 needs T006 and T007.
- The stories are independent in content but share `README.md`, so they run one after another in priority order.
  Each leaves the community-files test green.
- T023 can run alongside T022 (different files).

## Parallel Opportunities

Little parallelism: two files, and every story edits `README.md`. Safe pairs:

```text
T002 (run the baseline test)        with  reading docs/architecture.md for T015–T017
T022 (README review)                with  T023 (shellcheck on the test file)
```

## Implementation Strategy

- **MVP**: Phases 1–3 (checks, skeleton, quick start). This alone fixes the main complaint: newcomers can run the
  platform from the README, and stale facts are gone.
- **Increment 2**: Phase 4 adds badges and diagrams.
- **Increment 3**: Phase 5 completes the documentation map; Phase 6 validates and reviews rendering.
- Commit after each phase; run `verify` in the foreground only.

---

## Phase 7: Convergence

- [X] T026 Extend the collapsed purchase-flow `sequenceDiagram` in `README.md` to start with the shopper browsing the catalog and adding a product to the cart (gateway → catalog, gateway → cart → catalog for the price) before "place order", keeping the README at or under 250 lines per FR-007 (partial)
- [X] T027 Add a one-line expected outcome after the `git clone` and `cd` blocks under `### Start` in `README.md` (for example "a `scalable-e-commerce-platform-kotlin` directory" and "you are at the repository root"), keeping the README at or under 250 lines, per FR-005 (partial)
- [X] T028 Add a workflow-badge mutation to test case "README checks catch a broken copy" in `scripts/tests/test_community_files.sh`: move the inline workflow-file assertion into a `readme_missing_workflows FILE` function printing `workflow behind the <file> badge`, call it from the README case, and assert it reports a fixture badge pointing at `actions/workflows/does-not-exist.yml/badge.svg`, per SC-004 (partial)
- [X] T029 Review the change to `frontend/tests/ui/checkout.test.tsx:243` (waits for the payment radio with `findByRole`), which no 006 artifact calls for, and commit it separately from the README work, per plan: scope (unrequested)
