# Implementation Plan: Developer Harness Hooks and Quality Gates

**Branch**: `001-harness-quality-gates` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-harness-quality-gates/spec.md`

## Summary

Formalise and complete the developer harness that already exists as a baseline
(`.claude/settings.json`, `.claude/hooks/post-edit-check.sh`, `.claude/hooks/stop-full-check.sh`):
a per-file gate on every `Write|Edit` (ktlint/detekt or ESLint plus the tests that cover the file),
an end-of-task gate on `Stop` (full `check`, incremental mutation testing, frontend lint/test) with
a last-green marker and a loop guard, and a central pull-request gate on GitHub Actions (same
checks plus a full Pitest run, per-module threshold with a non-decreasing ratchet, and a
surviving-mutant check on changed lines). The plan extends the baseline rather than rewriting it:
a small shared bash library (time budgets, bounded failure report, timing log), a one-run
`HOOK_BYPASS=1` escape hatch with a visible trace, a `run-gate.sh` single-command entry point, a
Pitest convention plugin in `build-logic/`, quiet-logging defaults, shell-level pipe tests with a
fixture monorepo, the `pr-gate.yml` workflow (sharing a reusable `verify.yml` with feature 002),
and `docs/harness.md`. Stryker for the future frontend is wired conditionally and stays inert until
a TypeScript package with `stryker.config.*` exists. See [research.md](research.md) for decisions.

## Technical Context

**Language/Version**: Bash 3.2+ compatible (macOS default shell) with `jq` for hook scripts; YAML
for GitHub Actions workflows; Gradle Kotlin DSL (Gradle 9.8, JDK 25) for the Pitest and
test-logging convention plugins in `build-logic/`. No unknowns.

**Primary Dependencies**: `jq`, `git`, coreutils `timeout` (or `gtimeout`, with a pure-bash
watchdog fallback), Gradle wrapper (feature 002), Pitest 1.30.0 with Gradle plugin
`info.solidsoft.pitest` 1.19.0 (optionally `com.arcmutate:pitest-kotlin-plugin`, see research D5),
Kotest 6.2.5 (runner under Pitest), GitHub Actions on a containerised self-hosted runner with a
private registry on the runner host (feature 004 research §4-5), StrykerJS 10 (deferred, frontend
not yet present).

**Storage**: Files only: `.claude/.cache/last-full-check` (last-green marker),
`.claude/.cache/bypass.log`, `.claude/.cache/gate-timing.log` (all git-ignored) and the committed
ratchet file `quality/mutation-baseline.json`.

**Testing**: Shell-level pipe tests under `.claude/hooks/tests/*.sh` that feed synthetic hook JSON
to the real scripts against a fixture monorepo with fake `gradlew`/`npx`/`npm` shims and assert
exit codes, stdout/stderr and recorded invocations; the same style for the PR-gate scripts under
`.github/scripts/tests/`. Pitest itself is exercised through fixture XML reports.

**Target Platform**: Developer machines (macOS and Linux) running Claude Code hooks; Linux
containerised self-hosted GitHub Actions runner.

**Project Type**: Tooling / developer-harness configuration (no application code).

**Performance Goals**: Per-file feedback within 60 s for 90 % of edits (SC-001); complete gate
within 15 min typical and 2 s when nothing changed since the last green run (SC-002).

**Constraints**: Time budgets 5 min per-file gate and 20 min complete gate (hard abort, reported as
failure); silent on success (zero bytes on stdout and stderr); failure report = check name +
exact command + at most 60 trailing log lines; non-interactive tooling only; mutation threshold
80 % and not decreasing; no Groovy; `HOOK_BYPASS` is never honoured in CI.

**Scale/Scope**: One monorepo, tens of Gradle modules (mutation only on `domain` and `application`
modules), one future frontend workspace; 2 hook scripts, 1 shared lib, 1 entry-point wrapper,
2 PR-gate scripts, 2 workflows, 2 convention plugins, 1 documentation page.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Kotlin-Idiomatic Monorepo (Gradle Kotlin DSL) | PASS | Pitest and test-logging wiring are Kotlin DSL convention plugins in `build-logic/`; versions go in the catalog; no Groovy. Hook scripts are bash by the settled decision (see Complexity Tracking). |
| II. Clean / Hexagonal Architecture with DDD | N/A | No application code. Mutation testing is scoped to `domain` and `application` modules, which supports the architecture. |
| III. Security by Design | PASS | Workflow uses `permissions: contents: read`, third-party actions pinned by SHA, no secrets, ephemeral runner, fork PRs require approval (004 §4); hooks never print environment or secrets; `HOOK_BYPASS` ignored in CI. No user-facing surface, so no new threat model beyond runner safeguards. |
| IV. Functional & Non-Blocking First | N/A | No request or event paths. |
| V. Layered Test Contract | PASS | Mandatory shell-level tests with a fixture monorepo are planned first in every story. Unit/integration/contract/acceptance layers are not applicable to bash tooling; the gates this feature builds run those layers of other features. |
| VI. Microservice Boundaries & Contracts | N/A | No service or contract edge. The gate runs Pact verification via `check` once present. |
| VII. TypeScript + React Frontend | PASS | Frontend lint/test/Stryker steps are conditional on a package existing; nothing is created for a frontend that does not exist yet. |
| VIII. Token-Efficient, Hook-Driven Harness Engineering | PASS | This feature is the implementation of the principle: both hook gates, quiet Gradle/npm defaults, incremental Pitest in the hook and full Pitest in CI, threshold >= 80 % non-decreasing, surviving-mutant justification in the PR. |

Post-design re-check (after Phase 1): unchanged, all PASS or N/A. Two deviations are recorded below.

Post-implementation re-check (2026-10-02, T081): I, III, V, VII PASS as designed (conventions in
`build-logic/`, versions only in the catalogue; `pull_request` only, `contents: read`, SHA pins, fork guard, no
secrets, `HOOK_BYPASS` ignored in CI; shell tests for hooks and PR-gate scripts plus TestKit tests for the
Pitest properties; Stryker inert until a frontend exists). VIII PASS with one further deviation, recorded
below: the end-of-task hook mutation-tests through `./gradlew -q verify`, which runs the full Pitest of every
`domain`/`application` module, and the incremental (changed classes only) run is just its fallback. Pitest
uses the exclusions fallback instead of the Kotlin plugin (Arcmutate licence outcome in research.md).

## Project Structure

### Documentation (this feature)

```text
specs/001-harness-quality-gates/
├── plan.md              # This file
├── research.md          # Phase 0 output: decisions D1-D8
├── quickstart.md        # Phase 1 output: dry-run, fail, force, bypass, PR-gate verification
├── spec.md              # Feature specification
├── checklists/
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

`data-model.md` and `contracts/` are intentionally omitted: the entities (Gate, Check, Check
Result, Last-Green Marker, Mutation Threshold, Bypass Record) are files and exit codes described in
research.md, and the only external interface is the Claude Code hook JSON/exit-code protocol.

### Source Code (repository root)

```text
.claude/
├── settings.json                  # EXISTING: hook wiring (PostToolUse Write|Edit, Stop); extended timeouts
├── hooks/
│   ├── post-edit-check.sh         # EXISTING: per-file gate; extended (discovery, budget, bypass)
│   ├── stop-full-check.sh         # EXISTING: end-of-task gate; extended (incremental Pitest, budget, bypass)
│   ├── run-gate.sh                # NEW: single-command manual entry (file <path> | complete | pr), honours HOOK_DRY_RUN
│   ├── lib/
│   │   └── common.sh              # NEW: run_budgeted, bounded tail/report, timing log, bypass, changed-since-marker
│   └── tests/
│       ├── run-all.sh             # NEW: runs every test-*.sh, silent on success
│       ├── test-*.sh              # NEW: pipe tests per story (synthetic hook JSON -> assert exit/output)
│       └── fixtures/monorepo/     # NEW: fake gradlew/npm/npx shims, domain/application/infrastructure modules, frontend package
└── .cache/                        # git-ignored runtime state
    ├── last-full-check            # last-green marker (mtime)
    ├── bypass.log                 # one line per bypassed run
    └── gate-timing.log            # one line per gate run (SC-001/SC-002 measurement)

build-logic/
└── src/main/kotlin/
    ├── harness.pitest-conventions.gradle.kts       # NEW: Pitest + threshold + XML report + Kotlin exclusions
    └── harness.test-logging-conventions.gradle.kts # NEW: FAILED-only test logging (Principle VIII)

gradle.properties                  # quiet defaults (file owned by feature 002; lines added here)
gradle/libs.versions.toml          # Pitest/plugin versions (catalog owned by feature 002; entries added here)
.npmrc                             # quiet npm defaults
quality/
└── mutation-baseline.json         # committed per-module score ratchet (may only increase)

.github/
├── workflows/
│   ├── verify.yml                 # reusable (workflow_call): `./gradlew -q check` + frontend lint/test; shared with feature 002
│   └── pr-gate.yml                # NEW: calls verify.yml, full Pitest, mutation gate, surviving-mutant check, `pr-gate` status
├── scripts/
│   ├── pr-gate.sh                 # NEW: orchestrator used by the workflow and by `run-gate.sh pr`; supports --dry-run
│   ├── mutation-gate.sh           # NEW: per-module threshold + ratchet from Pitest XML
│   ├── surviving-mutants.sh       # NEW: survivors on changed lines vs PR-body justifications
│   └── tests/                     # NEW: tests with fixture Pitest XML and diffs
└── pull_request_template.md       # owned by feature 003; bypass checklist and mutant-justification section added here

docs/
└── harness.md                     # NEW: gates, triggers, checks, budgets, run/dry-run/bypass, PR gate, token measurement
.gitignore                         # `.claude/.cache/` entry
```

**Structure Decision**: The harness lives where Claude Code requires it (`.claude/settings.json`
and `.claude/hooks/`), the central gate lives in `.github/`, build wiring lives in `build-logic/`
as convention plugins, and the only documentation page is `docs/harness.md`. Files owned by other
features (`gradle.properties`, `gradle/libs.versions.toml`, `.github/pull_request_template.md`,
`build-logic/`) are extended with additive lines only, so there is no overlap in intent.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Bash scripts for hooks instead of Kotlin/Gradle (Principle I prefers Kotlin tooling) | Claude Code hooks are executed as commands and must start in milliseconds; the baseline is already bash; `jq` and coreutils are available. A Gradle task or Kotlin script would pay JVM start-up on every edit. | A Gradle-based hook (`./gradlew harnessCheck`) was rejected because per-file latency budget (SC-001) is dominated by start-up; a Claude Code plugin was rejected because plugins add a packaging and distribution layer for two small scripts and would hide the behaviour from reviewers. Gradle plugins are still used wherever the logic is build configuration (Pitest, test logging). |
| Shell tests instead of the Principle V test layers | The deliverable is bash and YAML, so unit/integration/contract/Cucumber layers do not apply; the constitution requires the layers "that apply". | Rewriting the hooks in Kotlin only to unit-test them contradicts the settled decision and the latency constraint. Pipe tests against the real scripts with fake tool shims give the same regression protection. |
| Full Pitest in the end-of-task hook instead of incremental (Principle VIII: "incremental in hooks") | Feature 002 makes `check`, hence `verify`, depend on `pitest`, and the complete gate runs `verify`; a narrowed second run would mutate the changed classes twice and hold each changed class to the 80 % threshold on its own, a rule CI does not have and that cannot be justified locally (observed: `ServiceName` scores 75 % alone because of an uncoverable value-class getter that the missing Kotlin plugin would filter). Measured cost on the reference service: 18 s warm, 121 s with conventions rebuilt (docs/harness.md). | Incremental only (`verify -x pitest` plus the narrowed run) was rejected for the false blocks above; revisit when Arcmutate's Kotlin and history plugins are licensed or when the full run approaches the 20-minute budget (the narrowed path stays in `stop-full-check.sh` as the fallback and `-Pharness.mutation.classes` is supported by the convention). |

## Requirement Traceability

| Requirement | Artifact(s) |
|-------------|-------------|
| FR-001, FR-002 | `.claude/hooks/post-edit-check.sh` (discovery), `tests/test-post-edit-*.sh` |
| FR-003, FR-004, FR-005 | `.claude/hooks/stop-full-check.sh`, `.claude/.cache/last-full-check`, `tests/test-stop-*.sh` |
| FR-006, FR-007 | `.claude/hooks/lib/common.sh` (bounded report), `tests/test-quiet-success.sh`, `tests/test-failure-format.sh` |
| FR-008 | `stop-full-check.sh` loop guard (`stop_hook_active`), `tests/test-stop-loop-guard.sh` |
| FR-009, FR-010 | `post-edit-check.sh` and `stop-full-check.sh` path/tool guards, `tests/test-post-edit-ignore.sh`, `tests/test-stop-skip.sh` |
| FR-011 | `CI=1`, `--run`, `--passWithNoTests`, `</dev/null` in hooks; `tests/test-stop-frontend.sh` |
| FR-012 | `run_budgeted` in `lib/common.sh`, `.claude/settings.json` timeouts, `tests/test-*-budget.sh` |
| FR-013 | `gradle.properties`, `harness.test-logging-conventions.gradle.kts`, `.npmrc`, `.claude/settings.json` env, `tests/test-gradle-quiet-config.sh` |
| FR-014 | `.github/workflows/pr-gate.yml`, `.github/workflows/verify.yml`, `.github/scripts/pr-gate.sh` |
| FR-015 | `harness.pitest-conventions.gradle.kts` (native threshold), `.github/scripts/mutation-gate.sh`, `quality/mutation-baseline.json` |
| FR-016 | `.github/scripts/surviving-mutants.sh`, PR template justification section |
| FR-017 | `HOOK_DRY_RUN=1` in both hooks, `pr-gate.sh --dry-run`, `tests/test-dry-run.sh` |
| FR-018 | `.claude/hooks/run-gate.sh`, `docs/harness.md` |
| FR-019 | `HOOK_BYPASS=1` handling in `lib/common.sh`, `.claude/.cache/bypass.log`, PR template checklist, `tests/test-bypass.sh` |
| FR-020 | `docs/harness.md`, `tests/test-docs.sh` |
