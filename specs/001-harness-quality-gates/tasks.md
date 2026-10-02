---

description: "Task list for Developer Harness Hooks and Quality Gates"
---

# Tasks: Developer Harness Hooks and Quality Gates

**Input**: Design documents from `/specs/001-harness-quality-gates/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, quickstart.md. There is no data-model.md or contracts/ for this feature (see plan.md).

**Baseline**: `.claude/settings.json`, `.claude/hooks/post-edit-check.sh` and `.claude/hooks/stop-full-check.sh` already exist and already implement per-file lint plus targeted tests, the end-of-task gate, `HOOK_DRY_RUN`, the `stop_hook_active` loop guard and the last-green marker. Tasks below EXTEND these files (bypass, time budgets, incremental Pitest, conditional Stryker, shared library, tests, documentation, PR gate); none recreates them.

**Tests**: Mandatory (Constitution Principle V, applied to tooling). For this feature they are shell-level verification tasks: pipe-test scripts under `.claude/hooks/tests/*.sh` (and `.github/scripts/tests/*.sh`) that feed synthetic hook JSON to the real scripts against the fixture monorepo in `.claude/hooks/tests/fixtures/monorepo/` and assert exit codes, output and recorded tool invocations. Write the tests first and see them fail before implementing.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `- [ ] T### [P?] [US#?] Description with exact file path`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[US#]**: User story label, present only in user-story phases (US1 per-file feedback, US2 end-of-task gate, US3 pull-request gate, US4 quiet output, US5 documentation/dry-run/bypass)
- Paths are relative to the repository root `/Users/raphaelpantaleao/Workspace/scalable-e-commerce-platform-kotlin`
- Files owned by other features (`gradle.properties`, `gradle/libs.versions.toml`, `build-logic/`, `.github/pull_request_template.md`) receive additive changes only

## Path Conventions

- Hooks and tests: `.claude/hooks/`, `.claude/hooks/tests/`
- Central gate: `.github/workflows/`, `.github/scripts/`
- Build wiring: `build-logic/src/main/kotlin/`, `gradle.properties`, `gradle/libs.versions.toml`
- Documentation: `docs/harness.md`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Test scaffolding and repository hygiene so every later task can be verified without a real Gradle build or frontend (neither exists yet)

- [X] T001 Add the `.claude/.cache/` entry to `.gitignore` (create the file if absent) so the marker, `bypass.log` and `gate-timing.log` are never committed
- [X] T002 [P] Create the silent-on-success test runner `.claude/hooks/tests/run-all.sh` that executes every `.claude/hooks/tests/test-*.sh`, prints only failing test names and exits non-zero on any failure
- [X] T003 [P] Create the fixture monorepo `.claude/hooks/tests/fixtures/monorepo/` with `settings.gradle.kts`, module `services/demo/domain` (`build.gradle.kts` mentioning ktlint, detekt and pitest; `src/main/kotlin/demo/Price.kt`; `src/test/kotlin/demo/PriceSpec.kt`), module `services/demo/infrastructure` (`src/main/kotlin/demo/Repo.kt` with no covering test), a generated file `services/demo/domain/build/Gen.kt`, and a frontend package `frontend/web/` (`package.json` with lint and test scripts, `stryker.config.json`, `src/index.ts`)
- [X] T004 [P] Create fake tool shims `.claude/hooks/tests/fixtures/monorepo/gradlew`, `.claude/hooks/tests/fixtures/bin/npm` and `.claude/hooks/tests/fixtures/bin/npx` that append their arguments to `$FIXTURE_LOG`, sleep for `$FAKE_SLEEP` seconds, print `$FAKE_OUTPUT_LINES` lines, and exit with `$FAKE_EXIT`
- [X] T005 Capture golden dry-run output of the existing baseline hooks against the fixture (`HOOK_DRY_RUN=1` for both scripts) into `.claude/hooks/tests/fixtures/expected/baseline-dry-run.txt` and add `.claude/hooks/tests/test-baseline-golden.sh` that diffs against it, so the refactor in Phase 2 provably preserves behaviour (depends on T003, T004)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared library and test helpers that every story reuses; the existing hooks are refactored to use it with no behaviour change

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T006 [P] Create the test helper `.claude/hooks/tests/lib.sh` providing `assert_exit`, `assert_silent`, `assert_contains`, `assert_max_lines`, `assert_invoked`, `assert_not_invoked`, a temp copy of the fixture per test, and hook-JSON builders (`post_edit_json <file>`, `stop_json <active>`)
- [X] T007 [P] Write the failing unit test `.claude/hooks/tests/test-common.sh` for the shared library: `run_budgeted` kills a sleeping child after the budget and leaves no stray process, `bounded_tail` never returns more than N lines and appends an omitted-lines marker, `log_timing` appends one line to `.claude/.cache/gate-timing.log`
- [X] T008 Create `.claude/hooks/lib/common.sh` with `hook_root`, `run_budgeted` (uses `timeout -k 5`, else `gtimeout`, else a bash watchdog that kills the child process tree; accepts remaining-time arguments), `bounded_tail`, and `log_timing`, so T007 passes
- [X] T009 Refactor `.claude/hooks/post-edit-check.sh` to source `.claude/hooks/lib/common.sh` and use its helpers in place of the inline `run_in` and `tail` code, with identical behaviour (depends on T008)
- [X] T010 [P] Refactor `.claude/hooks/stop-full-check.sh` to source `.claude/hooks/lib/common.sh` and use its helpers in place of the inline `run_in` and `tail` code, with identical behaviour (depends on T008)
- [X] T011 Run `.claude/hooks/tests/run-all.sh` and confirm T005 and T007 pass with both refactored hooks

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Immediate feedback on every file change (Priority: P1) 🎯 MVP

**Goal**: Every `Write|Edit` triggers style checks and only the covering tests for that file; silent on success, short diagnosis and a block on failure, with a 5-minute budget and correct ignore/skip rules (FR-001, FR-002, FR-006, FR-009, FR-010, FR-011, FR-012, SC-001).

**Independent Test**: Pipe synthetic `PostToolUse` JSON for a fixture file whose covering test fails and observe exit 2 with name, command and at most 60 lines; fix the fake and observe exit 0 with zero output bytes.

### Tests for User Story 1 (write first, they must FAIL before implementation) ⚠️

- [X] T012 [P] [US1] Test `.claude/hooks/tests/test-post-edit-pass.sh`: an edit of `Price.kt` with passing shims exits 0 and produces zero bytes on stdout and stderr (acceptance 1)
- [X] T013 [P] [US1] Test `.claude/hooks/tests/test-post-edit-fail.sh`: a failing shim makes the hook exit 2 and stderr contains the check name, the line starting with `$ ` holding the exact command, and at most 60 trailing log lines (acceptance 2)
- [X] T014 [P] [US1] Test `.claude/hooks/tests/test-post-edit-discovery.sh`: `Price.kt` runs `:services:demo:domain:test --tests *.PriceSpec`; `Repo.kt` (no covering test) runs style tasks only with no `:test` and no failure; Kotest naming `*Spec`, `*Test`, `*PropertyTest` is discovered; editing a test file runs that class (acceptance 3, FR-002)
- [X] T015 [P] [US1] Test `.claude/hooks/tests/test-post-edit-ignore.sh`: files under `build/`, `node_modules/`, `.gradle/`, `dist/`, `.claude/`, `.specify/`, outside the repo root, or nonexistent cause zero tool invocations; absent `gradlew` or `package.json` exits 0 silently (acceptance 4, FR-009, FR-010)
- [X] T016 [P] [US1] Test `.claude/hooks/tests/test-post-edit-budget.sh`: with `FAKE_SLEEP=30` and `HOOK_BUDGET_SECONDS=2` the hook exits 2 reporting `TIMEOUT after 2s` for the named check and no shim process remains afterwards (FR-012, edge case on time budget)
- [X] T017 [P] [US1] Test `.claude/hooks/tests/test-post-edit-concurrent.sh`: two simultaneous invocations (one passing, one failing) report independently, using distinct temp logs, with no cross-contamination (edge case on concurrent changes)
- [X] T018 [P] [US1] Test `.claude/hooks/tests/test-post-edit-typescript.sh`: a `.ts` edit in `frontend/web/` runs eslint, `tsc --noEmit` and `vitest related --run --passWithNoTests` with `CI=1` set, and `prettier --check` only when a Prettier config exists (FR-011)

### Implementation for User Story 1

- [X] T019 [US1] Extend test discovery in `.claude/hooks/post-edit-check.sh`: match `<Base>*.kt` across all `src/*[tT]est*/` source sets, cap at 20 classes, quote glob arguments, fall back to style-only when none found (D2 in research.md)
- [X] T020 [US1] Run every check in `.claude/hooks/post-edit-check.sh` through `run_budgeted` with a 300 s total deadline (override `HOOK_BUDGET_SECONDS`), reporting expiry as a failed check named `<check>: TIMEOUT after <N>s` (FR-012)
- [X] T021 [US1] Add the conditional `prettier --check` step and ensure `CI=1`, `--run` and `</dev/null` for the TypeScript path in `.claude/hooks/post-edit-check.sh` (Stryker is not part of the per-file gate)
- [X] T022 [US1] Record per-run duration with `log_timing` in `.claude/hooks/post-edit-check.sh` so SC-001 can be measured from `.claude/.cache/gate-timing.log` (no stdout output)
- [X] T023 [US1] Raise the `PostToolUse` hook `timeout` in `.claude/settings.json` from 300 to 330 so the internal 300 s budget and its report fire before the harness kills the hook
- [X] T024 [US1] Run `.claude/hooks/tests/run-all.sh` and confirm all User Story 1 tests pass

**Checkpoint**: User Story 1 is fully functional and testable independently (MVP)

---

## Phase 4: User Story 2 - End-of-task gate (Priority: P1)

**Goal**: At `Stop`, run the complete gate (full `check`, incremental Pitest on changed `domain`/`application` classes, frontend lint/test, Stryker when configured) only when relevant sources changed since the last green run, block once, never trap, and respect a 20-minute total budget (FR-003, FR-004, FR-005, FR-008, FR-010, FR-011, FR-012, SC-002, SC-004).

**Independent Test**: Feed `{"stop_hook_active":false}` with a failing fake `gradlew` and observe exit 2 with a concise report; feed `{"stop_hook_active":true}` and observe exit 0 with a `systemMessage`; make the fake pass and observe silence plus a refreshed marker, then a repeated run that exits in under 2 s.

### Tests for User Story 2 (write first, they must FAIL before implementation) ⚠️

- [X] T025 [P] [US2] Test `.claude/hooks/tests/test-stop-skip.sh`: marker newer than all sources exits 0 silently in under 2 s with zero tool invocations; absent marker runs the full gate; repository without `settings.gradle.kts` or `package.json` is a silent no-op (acceptance 2 and 4, SC-002)
- [X] T026 [P] [US2] Test `.claude/hooks/tests/test-stop-block.sh`: a failing `check` shim exits 2 with the report on stderr and leaves the marker unchanged; passing shims exit 0, print nothing and refresh the marker (acceptance 1, FR-005)
- [X] T027 [P] [US2] Test `.claude/hooks/tests/test-stop-loop-guard.sh`: simulate a persistently failing check; `stop_hook_active=false` yields exit 2, `stop_hook_active=true` yields exit 0 with the same report in a JSON `systemMessage`; never two blocks in a row (acceptance 3, SC-004, FR-008)
- [X] T028 [P] [US2] Test `.claude/hooks/tests/test-stop-frontend.sh`: the fixture package gets `lint` and `test` run with `CI=1` and stdin closed, Stryker `run --incremental` only when `stryker.config.*` exists, and a package without scripts is skipped (acceptance 5, FR-011)
- [X] T029 [P] [US2] Test `.claude/hooks/tests/test-stop-mutation.sh`: a changed `services/demo/domain/src/main/kotlin/demo/Price.kt` invokes `pitest -Pharness.mutation.classes=demo.Price*`; a change only in `infrastructure` or only `*.md` does not invoke Pitest; a Pitest shim exiting 0 with no mutants passes (FR-004, edge case on no mutants)
- [X] T030 [P] [US2] Test `.claude/hooks/tests/test-stop-budget.sh`: with `HOOK_BUDGET_SECONDS=3` and several slow shims, the total elapsed time stays within the shared deadline, the overrun check is reported as `TIMEOUT`, and no process remains (FR-012)

### Implementation for User Story 2

- [X] T031 [US2] Add `changed_since_marker` to `.claude/hooks/lib/common.sh` returning the list of changed relevant files since `.claude/.cache/last-full-check` (pruning `build`, `node_modules`, `.gradle`, `.claude`, `.specify`, `dist`), and use it in `.claude/hooks/stop-full-check.sh` in place of the single `-quit` probe while keeping the fast skip path
- [X] T032 [US2] Replace the unconditional full `pitest` call in `.claude/hooks/stop-full-check.sh` with an incremental step: map changed `domain`/`application` `.kt` files to class globs from their `package` line and file name, run `pitest -Pharness.mutation.classes=<globs>` only in modules that apply `harness.pitest-conventions`, and skip when no such file changed (depends on T031)
- [X] T033 [US2] Keep the Stryker step in `.claude/hooks/stop-full-check.sh` conditional on `stryker.config.*` in a package, run with `--incremental` and `CI=1`; document it as deferred until a frontend exists (no new files)
- [X] T034 [US2] Give `.claude/hooks/stop-full-check.sh` a 1200 s total deadline (override `HOOK_BUDGET_SECONDS`) shared across checks via `run_budgeted`, reporting an expired check as `TIMEOUT` and recording the duration with `log_timing`
- [X] T035 [US2] Make marker handling in `.claude/hooks/stop-full-check.sh` safe: refresh it atomically only after every check passed, never on failure, treat a missing marker as a full run, and keep the `stop_hook_active` loop guard emitting the report as a JSON `systemMessage` with exit 0 (FR-008)
- [X] T036 [US2] Raise the `Stop` hook `timeout` in `.claude/settings.json` from 1200 to 1230 so the internal 1200 s budget and its report fire first
- [X] T037 [US2] Run `.claude/hooks/tests/run-all.sh` and confirm all User Story 2 tests pass

**Checkpoint**: User Stories 1 and 2 both work independently

---

## Phase 5: User Story 3 - Pull-request gate enforced centrally (Priority: P2)

**Goal**: A GitHub Actions gate on the containerised self-hosted runner runs the verify command (shared with feature 002), a full Pitest run, per-module threshold with a non-decreasing ratchet, and the surviving-mutant check on changed lines, and exposes one consolidated `pr-gate` status (FR-014, FR-015, FR-016, SC-005).

**Independent Test**: Run `.github/scripts/mutation-gate.sh` against a fixture Pitest XML whose module scores below its threshold and observe a failure naming module, threshold and score; run `.github/scripts/surviving-mutants.sh` with a survivor on a changed line and observe failure until the PR body justifies it.

### Tests for User Story 3 (write first, they must FAIL before implementation) ⚠️

- [X] T038 [P] [US3] Create `.github/scripts/tests/run-all.sh` (silent on success) and fixtures under `.github/scripts/tests/fixtures/` (Pitest `mutations.xml` files for modules `domain-ok`, `domain-low` and `domain-empty`, a `diff.patch` with added lines, PR body samples with and without justifications, `baseline.json`)
- [X] T039 [P] [US3] Test `.github/scripts/tests/test-mutation-gate.sh`: score below threshold fails naming module, threshold and score; score below the committed baseline fails even if above 80; undeclared threshold defaults to 80; a module with no mutants passes; lowering a baseline value relative to the base branch copy fails (FR-015, edge cases)
- [X] T040 [P] [US3] Test `.github/scripts/tests/test-surviving-mutants.sh`: a survivor on an added line fails unless `path:line reason` appears under `## Mutant justifications` in the PR body; a survivor on an unchanged line passes; killed mutants are ignored; comment-only change with no mutants passes (FR-016)
- [X] T041 [P] [US3] Test `.github/scripts/tests/test-pr-gate-workflow.sh` using `yq` or `grep` on `.github/workflows/pr-gate.yml` and `.github/workflows/verify.yml`: `pull_request` trigger only (no `pull_request_target`), `permissions: contents: read`, every `uses:` pinned to a 40-character SHA, a final job named `pr-gate`, the full `pitest` task without `harness.mutation.classes`, `./gradlew -q check` present, and `HOOK_BYPASS` not referenced

### Implementation for User Story 3

- [X] T042 [P] [US3] Add Pitest 1.30.0, the Gradle plugin `info.solidsoft.pitest` 1.19.0 and the compatible `pitest-junit5-plugin` entries to `gradle/libs.versions.toml` (additive lines in the catalog owned by feature 002)
- [X] T043 [P] [US3] Create the convention plugin `build-logic/src/main/kotlin/harness.pitest-conventions.gradle.kts`: default threshold 80 with a per-module override extension, `failWhenNoMutations = false`, JUnit 5 platform, XML and HTML reports with `timestampedReports = false`, history files, `targetClasses` override from `harness.mutation.classes`, Kotlin-synthetic exclusions and `avoidCallsTo`, optional Arcmutate dependency behind `harness.pitest.arcmutate` (default false), quiet logging
- [X] T044 [US3] Resolve the Arcmutate licence question for this public MIT repository (open follow-up from specs/004-ecommerce-platform-mvp/research.md section 14): record the outcome in a new "Arcmutate licence outcome" section appended to `specs/001-harness-quality-gates/research.md` and set `harness.pitest.arcmutate` accordingly in `gradle.properties`
- [X] T045 [P] [US3] Create `quality/mutation-baseline.json` (initially an empty JSON object keyed by Gradle module path) as the committed score ratchet
- [X] T046 [US3] Create `.github/scripts/mutation-gate.sh`: read each `build/reports/pitest/mutations.xml`, compute per-module score, fail with `module <path>: score <S>% < threshold <T>%` below threshold or below the baseline, compare the baseline file against the base branch copy so it can only increase, accept `--dry-run` (depends on T039)
- [X] T047 [US3] Create `.github/scripts/surviving-mutants.sh`: intersect `SURVIVED` mutants in the Pitest XML with added lines from `git diff -U0 <base>...HEAD` and require `path:line reason` entries in the `## Mutant justifications` section of the PR body read from `$GITHUB_EVENT_PATH` (no network), accept `--dry-run` (depends on T040)
- [X] T048 [P] [US3] Create the reusable workflow `.github/workflows/verify.yml` (`workflow_call`) that runs `./gradlew -q check` and, per `package.json`, frontend `lint` and `test` with the package manager detected from the lockfile; reused by feature 002's CI requirement
- [X] T049 [US3] Create `.github/scripts/pr-gate.sh` that runs, in order, the verify steps, the full `./gradlew -q pitest`, `.github/scripts/mutation-gate.sh` and `.github/scripts/surviving-mutants.sh`, bounds each failure report to 60 lines and writes a summary of at most 20 lines to `$GITHUB_STEP_SUMMARY` when set (depends on T046, T047)
- [X] T050 [US3] Create `.github/workflows/pr-gate.yml`: `pull_request` trigger, ephemeral containerised self-hosted runner labels, `permissions: contents: read`, SHA-pinned actions, `concurrency`, `timeout-minutes: 30`, jobs `verify` (calls `verify.yml`), `mutation` (full Pitest plus the two scripts, Stryker only when configured) and a final `pr-gate` job that aggregates them as the single required status (depends on T048, T049)
- [X] T051 [US3] Add the `## Mutant justifications` section (format `path:line reason`) to `.github/pull_request_template.md` (file owned by feature 003; create a minimal one if absent and let feature 003 extend it)
- [X] T052 [US3] Run `.github/scripts/tests/run-all.sh` and confirm all User Story 3 tests pass

**Checkpoint**: Pull requests show one consolidated `pr-gate` status enforcing threshold, ratchet and survivor rules

---

## Phase 6: User Story 4 - Quiet, token-efficient output (Priority: P2)

**Goal**: Passing checks add zero output; failing checks show only identity, exact command and a bounded excerpt; Gradle and npm tooling run with quiet defaults (FR-006, FR-007, FR-013, SC-003, SC-007).

**Independent Test**: Run both hooks against passing shims and confirm zero bytes on stdout and stderr; run against a failing shim emitting 500 lines and confirm only the check name, command and at most 60 lines are shown.

### Tests for User Story 4 (write first, they must FAIL before implementation) ⚠️

- [X] T053 [P] [US4] Test `.claude/hooks/tests/test-quiet-success.sh`: every passing scenario of both hooks (including a skipped gate and an ignored file) yields exactly zero bytes on stdout and stderr, with `HOOK_DRY_RUN` unset (FR-007, SC-003)
- [X] T054 [P] [US4] Test `.claude/hooks/tests/test-failure-format.sh`: a shim printing 500 lines yields a report with the check name, a `$ ` command line, an `(N earlier lines omitted)` marker and at most 60 log lines in the per-file gate and 40 in the complete gate (FR-006)
- [X] T055 [P] [US4] Test `.claude/hooks/tests/test-gradle-quiet-config.sh`: `gradle.properties` contains `org.gradle.console=plain` and `org.gradle.logging.level=quiet`; `build-logic/src/main/kotlin/harness.test-logging-conventions.gradle.kts` limits events to `FAILED`, sets `exceptionFormat = FULL` and `showStandardStreams = false`; `.claude/settings.json` env sets `NPM_CONFIG_LOGLEVEL=error`, `NO_COLOR=1` and the quiet Gradle options; `.npmrc` sets `loglevel=error` (FR-013)

### Implementation for User Story 4

- [X] T056 [US4] Audit `.claude/hooks/post-edit-check.sh` and `.claude/hooks/stop-full-check.sh` so every child's stdout and stderr go only to the per-run `mktemp` log and nothing is printed on success (including `mktemp`, `jq` and `cd` stderr); only `HOOK_DRY_RUN` prints
- [X] T057 [US4] Add `report_failure` to `.claude/hooks/lib/common.sh` producing `<gate> check FAILED: <check>`, the `$ (cd <dir> && <command>)` line and `bounded_tail` output with the omitted-lines marker, with documented constants `HOOK_TAIL_LINES=60` (per-file) and `40` (complete gate), and use it from both hooks
- [X] T058 [P] [US4] Create `build-logic/src/main/kotlin/harness.test-logging-conventions.gradle.kts` configuring every `Test` task with `events("failed")`, `exceptionFormat = TestExceptionFormat.FULL` and `showStandardStreams = false`
- [X] T059 [P] [US4] Add the quiet defaults to `gradle.properties` (`org.gradle.console=plain`, `org.gradle.logging.level=quiet`, `org.gradle.warning.mode=none`) as additive lines (file owned by feature 002)
- [X] T060 [P] [US4] Create `.npmrc` at the repository root with `loglevel=error`, `fund=false` and `audit=false` so npm and pnpm scripts run silently once a frontend exists
- [X] T061 [US4] Run `.claude/hooks/tests/run-all.sh` and confirm all User Story 4 tests pass

**Checkpoint**: Output across the harness is quiet on success and bounded on failure

---

## Phase 7: User Story 5 - Harness is documented and controllable (Priority: P3)

**Goal**: Contributors can read how every gate works, run it with one command, dry-run it, and bypass it once with a trace reviewers can see (FR-017, FR-018, FR-019, FR-020, SC-006).

**Independent Test**: Following `docs/harness.md`, run `HOOK_DRY_RUN=1 .claude/hooks/run-gate.sh complete` and confirm it prints the commands without executing any; run with `HOOK_BYPASS=1` and confirm exit 0 plus one new line in `.claude/.cache/bypass.log`.

### Tests for User Story 5 (write first, they must FAIL before implementation) ⚠️

- [X] T062 [P] [US5] Test `.claude/hooks/tests/test-dry-run.sh`: `HOOK_DRY_RUN=1` on `post-edit-check.sh`, `stop-full-check.sh`, `run-gate.sh file|complete|pr` and `.github/scripts/pr-gate.sh --dry-run` prints `DRY` lines for each command, records zero shim invocations and exits 0 (FR-017)
- [X] T063 [P] [US5] Test `.claude/hooks/tests/test-bypass.sh`: `HOOK_BYPASS=1` makes both hooks exit 0 silently and append one `gate=... user=... file=... reason=...` line to `.claude/.cache/bypass.log`; values other than `1` do not bypass; a second run without the variable runs the checks; `CI=true` or `GITHUB_ACTIONS=true` ignores the bypass (FR-019)
- [X] T064 [P] [US5] Test `.claude/hooks/tests/test-run-gate.sh`: `run-gate.sh file <path>` builds the synthetic hook JSON and forwards exit codes, `complete` runs the Stop gate with `stop_hook_active=false`, unknown arguments print usage and exit 64 (FR-018)
- [X] T065 [P] [US5] Test `.claude/hooks/tests/test-docs.sh`: `docs/harness.md` names every gate with trigger, checks, budget, run, dry-run and bypass commands, mentions the 60/40 line caps and the exclusions fallback, and every script or path it references exists (FR-020, SC-006)
- [X] T066 [P] [US5] Test `.claude/hooks/tests/test-pr-template.sh`: `.github/pull_request_template.md` contains the "Gate bypasses" checklist item and the `## Mutant justifications` section, and `.github/workflows/pr-gate.yml` fails a pull request whose body lacks the bypass section (FR-019)

### Implementation for User Story 5

- [X] T067 [US5] Add `hook_bypass` to `.claude/hooks/lib/common.sh`: when `HOOK_BYPASS` equals `1` and neither `CI` nor `GITHUB_ACTIONS` is `true`, append `<ISO-8601 UTC> gate=<name> user=$USER file=<path or -> reason=<HOOK_BYPASS_REASON or ->` to `.claude/.cache/bypass.log` and signal the caller to exit 0
- [X] T068 [US5] Call `hook_bypass` in `.claude/hooks/post-edit-check.sh` after dry-run handling and before any check runs (depends on T067)
- [X] T069 [P] [US5] Call `hook_bypass` in `.claude/hooks/stop-full-check.sh` after dry-run handling and before any check runs (depends on T067)
- [X] T070 [US5] Create `.claude/hooks/run-gate.sh` with subcommands `file <path>`, `complete` and `pr` that build the synthetic hook JSON, call the matching script (`pr` calls `.github/scripts/pr-gate.sh`), honour `HOOK_DRY_RUN` and `HOOK_BYPASS`, and print usage with exit 64 otherwise
- [X] T071 [US5] Add `--dry-run` and `HOOK_DRY_RUN` handling to `.github/scripts/pr-gate.sh`, `.github/scripts/mutation-gate.sh` and `.github/scripts/surviving-mutants.sh` so each prints `DRY [...]:` lines instead of executing (depends on T049)
- [X] T072 [US5] Add the "Gate bypasses" checklist item (`none`, or paste lines from `.claude/.cache/bypass.log`) to `.github/pull_request_template.md` and a `pr-gate` step in `.github/workflows/pr-gate.yml` that fails when the PR body lacks that section (depends on T051, T050)
- [X] T073 [US5] Write `docs/harness.md` covering, per gate (per-file, end-of-task, pull-request): trigger, checks, time budget, failure report format and caps, how to run (`run-gate.sh`), dry-run and bypass, plus the last-green marker, loop guard, runner safeguards from specs/004-ecommerce-platform-mvp/research.md section 4, the Pitest Arcmutate-versus-exclusions decision and the deferred Stryker step
- [X] T074 [US5] Extend `docs/harness.md` with the branch-protection instruction (require the `pr-gate` status, feature 003), the SC-007 measurement method using `rtk`/`tokensave` reports, and how to read `.claude/.cache/gate-timing.log` for SC-001 and SC-002 (same file as T073, so sequential)
- [X] T075 [US5] Run `.claude/hooks/tests/run-all.sh` and `.github/scripts/tests/run-all.sh` and confirm all User Story 5 tests pass

**Checkpoint**: All five user stories are independently functional and documented

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [X] T076 [P] Add a `hook-tests` job to `.github/workflows/pr-gate.yml` that runs `.claude/hooks/tests/run-all.sh` and `.github/scripts/tests/run-all.sh`, included in the `pr-gate` aggregation
- [X] T077 [P] Run `shellcheck` over `.claude/hooks/*.sh`, `.claude/hooks/lib/common.sh`, `.claude/hooks/tests/*.sh` and `.github/scripts/*.sh` and fix all findings (record in `docs/harness.md` if a directive must be disabled)
- [X] T078 [P] Final review of `.claude/settings.json`: hook timeouts 330 and 1230, status messages accurate, no `HOOK_BYPASS` entry, permissions unchanged
- [X] T079 Once feature 002's reference service exists, measure SC-001 (90th percentile of per-file runs) and SC-002 (complete gate, and under 2 s when unchanged) from `.claude/.cache/gate-timing.log` and record the results in `docs/harness.md`
- [ ] T080 Run every command in `specs/001-harness-quality-gates/quickstart.md` and confirm the outcome-to-criterion map (SC-001 to SC-007)
- [X] T081 Re-run the Constitution Check in `specs/001-harness-quality-gates/plan.md` against the implemented files and run `/speckit-analyze` for spec, plan and tasks consistency

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion (T007 needs the runner and fixtures) - BLOCKS all user stories
- **User Stories (Phases 3-7)**: All depend on Foundational completion
  - US1 and US2 (both P1) share `.claude/hooks/lib/common.sh` but edit different scripts, so they can proceed in parallel after Foundational
  - US3 (P2) is independent of the hooks (scripts and workflows) and can run in parallel with US1/US2
  - US4 (P2) touches both hooks and the build wiring; schedule after US1 and US2 to avoid edit conflicts, or coordinate on the shared files
  - US5 (P3) edits both hooks and adds the entry point; schedule after US1, US2 and US3
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Starts after Foundational; no dependency on other stories
- **User Story 2 (P1)**: Starts after Foundational; no dependency on US1 (different script)
- **User Story 3 (P2)**: Starts after Foundational; no dependency on US1/US2; reuses the `verify` command shared with feature 002
- **User Story 4 (P2)**: Starts after Foundational; best after US1/US2 because the audit touches both hooks
- **User Story 5 (P3)**: T071 and T072 require US3 outputs (T049, T050); hook bypass requires Foundational only

### Within Each User Story

- Tests are written first and MUST fail before implementation
- Library functions before the scripts that call them
- Scripts before workflows that orchestrate them
- Story complete (green task) before moving to the next priority

### Parallel Opportunities

- Setup tasks T002, T003 and T004 touch different files
- T006 and T007 are independent; T009 and T010 edit different scripts
- All test tasks within a story are marked [P] (each is its own file)
- US3 implementation: T042, T043, T045 and T048 are independent files
- US4 implementation: T058, T059 and T060 are independent files

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (each is a separate file):
Task: "Test .claude/hooks/tests/test-post-edit-pass.sh (silent success)"
Task: "Test .claude/hooks/tests/test-post-edit-fail.sh (exit 2, name, command, <=60 lines)"
Task: "Test .claude/hooks/tests/test-post-edit-discovery.sh (covering tests, style-only fallback)"
Task: "Test .claude/hooks/tests/test-post-edit-ignore.sh (ignored paths, missing tools)"
Task: "Test .claude/hooks/tests/test-post-edit-budget.sh (TIMEOUT, no stray process)"

# Then implement sequentially in .claude/hooks/post-edit-check.sh (same file):
Task: "Extend discovery", "Wire the 300 s budget", "Add prettier/CI handling", "Record timing"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: run `.claude/hooks/tests/run-all.sh` and the User Story 1 independent test
5. The per-file gate is the tightest feedback loop and the base for the rest

### Incremental Delivery

1. Setup + Foundational: library, fixture and test runner ready
2. Add User Story 1 and test independently (MVP)
3. Add User Story 2 (end-of-task gate with incremental Pitest) and test independently
4. Add User Story 3 (central PR gate) and test independently
5. Add User Story 4 (quiet output guarantees) and User Story 5 (documentation, dry-run, bypass)
6. Polish, then measure SC-001 and SC-002 on feature 002's reference service

### Parallel Team Strategy

1. Team completes Setup + Foundational together
2. Then: Developer A takes US1, Developer B takes US2, Developer C takes US3 (hooks and workflows are separate files)
3. US4 and US5 follow once US1 to US3 have landed, because they touch the same two scripts

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [US#] label maps a task to its user story; Setup, Foundational and Polish tasks carry none
- Each user story should be independently completable and testable
- Verify tests fail before implementing; commit after each task or logical group
- Existing hooks are extended, never rewritten: T005 guards the refactor
- Stryker tasks are conditional and inert until a frontend package with `stryker.config.*` exists
- Open follow-up carried from feature 004: the Arcmutate licence outcome (T044); the plan works either way through `harness.pitest.arcmutate`
- Avoid: vague tasks, same-file conflicts between [P] tasks, bypass logic anywhere in CI
