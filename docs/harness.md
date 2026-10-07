# Developer harness: hooks and quality gates

The harness keeps quality feedback tight for people and for agents. It has three gates. Each gate is
quiet when it passes and, when it fails, prints the failed check, the exact command and a short,
bounded excerpt of the output (Constitution Principle VIII, specs/001-harness-quality-gates).

| Gate | Trigger | Where it runs | Budget | Report cap |
| --- | --- | --- | --- | --- |
| Per-file gate | `PostToolUse` after every `Write` or `Edit` | `.claude/hooks/post-edit-check.sh` | 300 s | 60 lines |
| End-of-task gate | `Stop` hook, when the agent finishes a task | `.claude/hooks/stop-full-check.sh` | 1200 s | 40 lines per check |
| Pull-request gate | `pull_request` on GitHub | `.github/workflows/pr-gate.yml` | 30 minutes | 60 lines per step, 20-line summary |

Hooks are registered in `.claude/settings.json` (timeouts 330 s and 1230 s, a little above the internal
budgets so the script's own timeout and report fire first). Shared code lives in
`.claude/hooks/lib/common.sh`.

## Per-file gate

- **Trigger**: `PostToolUse` with matcher `Write|Edit`. Files under `build/`, `node_modules/`, `.gradle/`,
  `dist/`, `.claude/`, `.specify/`, outside the repository, or that do not exist are ignored.
- **Checks**: for a Kotlin file, `ktlintCheck` and `detekt` of its Gradle module (when the build configures
  them) plus the tests that cover it: every test class named `<File>*` (Kotest `*Spec`, `*Test`,
  `*PropertyTest`) in any `src/*[tT]est*` source set, at most 20; editing a test file runs that class; no
  covering test means style checks only. A `*.gradle.kts` file runs `gradle help` (script compilation). A
  TypeScript file (`.ts`, `.tsx`, `.mts`, `.cts`; for the storefront everything under `frontend/src`,
  `frontend/tests`, `frontend/pact` and `frontend/acceptance`) runs, inside its nearest `package.json`
  directory, `eslint --max-warnings=0 <file>` when an ESLint config exists, `tsc --noEmit` when a
  `tsconfig.json` exists, `prettier --check <file>` only when a Prettier config exists (the storefront has
  `frontend/.prettierrc`) and `vitest related <file> --run --passWithNoTests` (the Vitest tests that import
  the file), with `CI=1` and stdin closed, all quiet and reporting failures only. Stryker is not part of the
  per-file gate. Missing tools (no `gradlew`, no `package.json`) make the gate a silent no-op.
- **Budget**: 300 s for all checks of one edit, shared. A check that runs over is killed and reported as
  `<check>: TIMEOUT after <N>s`; no child process is left behind (the Gradle daemon is intentionally left to
  idle out).
- **Failure**: exit code 2 (blocks the agent) with
  `post-edit check FAILED: <check>`, a `$ (cd <dir> && <command>)` line and at most 60 lines of the tool
  output; longer output starts with `(N earlier lines omitted)`.
- **Run by hand**: `.claude/hooks/run-gate.sh file <path>`.
- **Dry run**: `HOOK_DRY_RUN=1 .claude/hooks/run-gate.sh file <path>` prints `DRY [...]:` lines, runs nothing.
- **Bypass**: `HOOK_BYPASS=1 .claude/hooks/run-gate.sh file <path>` (see Bypass below).

## End-of-task gate

- **Trigger**: the Stop hook. It does nothing without `settings.gradle.kts`, `build.gradle.kts` or a
  `package.json`, and nothing when no relevant source changed since the last green run.
- **Checks**: `./gradlew -q verify`, the root lifecycle task of feature 002 ([build.md](build.md)): toolchain
  and version-literal checks, the build-logic tests, and every module's ktlint, detekt, test layers,
  architecture rules and full Pitest run, plus `frontend/` lint and test once that package exists. Then
  incremental Pitest (below); for every other frontend package `lint` and `test` (package manager from the
  lockfile, `CI=1`, stdin closed; `frontend/package.json` itself is left to `verify`, so it never runs twice);
  `npx --no-install stryker run --incremental` when a `stryker.config.*` exists in that package. Stryker was
  deferred until a frontend existed; since feature 005 `frontend/stryker.config.json` exists, so the step is
  active: it mutates `src/domain`, `src/app` and `src/telemetry`, reuses the incremental result in
  `frontend/build/stryker-incremental.json` (only mutants of changed code and tests are re-run) and breaks below
  80 %. The CI pipeline of the storefront (`.github/workflows/storefront.yml`) runs the full suite
  (`npm run mutate`).
- **Bootstrapping**: a Gradle answer of the form `Task '...' not found` (no `verify` or `pitest` task, as in a
  repository that predates feature 002) is treated as "skipped: no such task yet": silent, not a failure.
- **Mutation testing of changed code**: `verify` already runs the full Pitest of every `domain` and
  `application` module (feature 002 makes `check` depend on `pitest`), which covers everything changed since
  the last green run and enforces the module threshold, as the pull-request gate does. The incremental step is
  the fallback for a build without a `verify` task: changed `src/main` `.kt` files of modules named `domain` or
  `application` are mapped to class globs (`<package>.<File>*`) and
  `./gradlew :<module>:pitest -Pharness.mutation.classes=<globs>` runs per module, only in modules that have a
  `pitest` task (probed once per run with `gradlew tasks --all`). It is not run after `verify`: the narrowed run
  would hold each changed class to the threshold on its own, a rule the pull-request gate does not have (there,
  survivors on changed lines are justified in the description instead), and it would mutate those classes twice.
  `-Pharness.mutation.classes` stays available for a targeted run by hand.
- **Last-green marker**: `.claude/.cache/last-full-check` is replaced atomically (dated at the start of the
  run) only after every check passed; a failure never touches it and a missing marker means a full run. A
  repeated run with no relevant change exits in under 2 s. Relevant files: `.kt .kts .ts .tsx .toml .json
  .feature .properties .yml .yaml .sql`, ignoring `build`, `node_modules`, `.gradle`, `.claude`, `.specify`,
  `dist`. To force a full run delete the marker.
- **Loop guard**: when the hook input has `stop_hook_active` set to true the agent was already blocked once;
  the hook then prints the same report as a JSON `systemMessage` and exits 0, so it never blocks twice in a row.
- **Budget**: 1200 s in total, shared by all checks (each gets the time remaining). The overrun check is
  reported as `<check>: TIMEOUT after <N>s`; checks that never got to start are listed as not run.
- **Failure**: exit code 2 and one consolidated report: per failed check
  `stop check FAILED: <check>`, the `$ (cd <dir> && <command>)` line and at most 40 lines of its output
  (same `(N earlier lines omitted)` marker).
- **Run by hand**: `.claude/hooks/run-gate.sh complete`. **Dry run**: `HOOK_DRY_RUN=1 .claude/hooks/run-gate.sh complete`.
  **Bypass**: `HOOK_BYPASS=1 .claude/hooks/run-gate.sh complete`.

## Pull-request gate

- **Trigger**: `pull_request` only (never `pull_request_target`) on the containerised self-hosted runner, with
  `permissions: contents: read`, SHA-pinned actions and a 30-minute job timeout.
- **Workflow**: `.github/workflows/pr-gate.yml`, on `pull_request` types `opened`, `synchronize`, `reopened` and
  `edited` (editing the description re-runs the gate with the new text; re-running an old run keeps the old
  text). Jobs:
  - `verify` calls the reusable `.github/workflows/verify.yml` (feature 002, `workflow_call`):
    `./gradlew -q verify`, which includes the full Pitest run of every `domain` and `application` module
    without class filter; with `mutation-reports: true` it hands the `mutations.xml` reports over as the
    `pitest-reports` artifact, so Pitest runs once per pull request.
  - `mutation` runs `.github/scripts/pr-gate.sh --skip-verify` on those reports:
    `.github/scripts/mutation-gate.sh` (per-module score, Pitest rounding, against the module threshold:
    `pitest { mutationThreshold.set(N) }`, default 80 %, never lower; against the module's entry in
    `quality/mutation-baseline.json`; and the baseline file itself may only go up compared with the base
    branch copy) then `.github/scripts/surviving-mutants.sh` (every `SURVIVED` or `NO_COVERAGE` mutant on a line
    added by `git diff -U0 <base>...HEAD` fails unless the description lists `path:line reason` under
    `## Mutant justifications`; the description comes from the event payload, no network). The `mutation` job
    installs the dependencies of every frontend package that has a lock file and a Stryker config
    (`frontend/`), and `pr-gate.sh` then runs the full `stryker run` of each such package (threshold 80 %, the
    `thresholds.break` of its `stryker.config.json`). The Pitest baseline file does not cover Stryker: the
    storefront's ratchet is the threshold in its own configuration.
  - `hook-tests` runs `.claude/hooks/tests/run-all.sh` and `.github/scripts/tests/run-all.sh`.
  - `pr-gate` (always runs, no checkout) fails when the description lacks a filled `## Gate bypasses` section
    and unless the three jobs above succeeded; it writes a summary of at most 20 lines. It is the single
    status that branch protection requires.
- **Raising the ratchet**: when a module's score improves, raise its entry in `quality/mutation-baseline.json`
  (keys are Gradle paths such as `":services:catalog:domain"`, values whole percentages) in the same pull
  request. Lowering or removing an entry of an existing module fails the gate.
- **Run by hand**: `.claude/hooks/run-gate.sh pr` (calls `.github/scripts/pr-gate.sh`: `./gradlew -q verify`,
  then the same two scripts in the same order). Locally the diff base is `origin/main` (or `PR_BASE_SHA`) and
  the description is read from `PR_BODY` when set; without a base the surviving-mutant step is skipped (in CI
  it fails). **Dry run**: `HOOK_DRY_RUN=1 .claude/hooks/run-gate.sh pr` or `.github/scripts/pr-gate.sh --dry-run`.
- **Failure**: `pr-gate step FAILED: <step>`, the `$ <command>` line and at most 60 lines of its output; the
  steps stop at the first failure.
- **Bypass**: none in CI. `HOOK_BYPASS` is ignored when `CI` or `GITHUB_ACTIONS` is `true`.

## Environment variables and exit codes

| Variable | Effect |
| --- | --- |
| `HOOK_DRY_RUN=1` | Print the commands as `DRY [...]: <command>`, run nothing, write no marker, log nothing. Evaluated before the bypass. |
| `HOOK_BYPASS=1` | Skip the gate for this run only; exactly `1`; ignored in CI. |
| `HOOK_BYPASS_REASON` | Free text stored in the bypass log line. |
| `HOOK_BUDGET_SECONDS` | Overrides the total budget (300 s per-file, 1200 s complete); used by the tests. |

Exit codes of the hooks: 0 pass, skip or no-op; 2 blocked with a report on stderr. `run-gate.sh` forwards them
and adds 64 for a usage error and 69 when the pull-request script is missing.

## Bypass

`HOOK_BYPASS=1` skips a gate once and appends one line to `.claude/.cache/bypass.log` (git-ignored):

    <ISO-8601 UTC> gate=<post-edit|stop|pr-local> user=<$USER> file=<path or -> reason=<reason or ->

Never put it in `.claude/settings.json`. The log is local, so reviewers see it through the pull request
template: the "Gate bypasses" item says `none` or contains the lines pasted from the log, and `pr-gate`
fails a pull request whose body lacks that section.

## Quiet output

Child tool output goes to a per-run temporary log and is shown only on failure. Defaults:
`org.gradle.console=plain`, `org.gradle.logging.level=quiet` and `org.gradle.warning.mode=none` in
`gradle.properties`; the test logging of feature 002's `kotlin-base` convention
(`build-logic/src/main/kotlin/kotlin-base.gradle.kts`: events `FAILED` only, full exception format, no standard
streams, configured for the quiet log level); `NPM_CONFIG_LOGLEVEL=error` and `NO_COLOR=1` in `.claude/settings.json`; `.npmrc` with
`loglevel=error`. The caps (60 lines per-file, 40 lines per check in the complete gate) are the constants
`HOOK_TAIL_LINES_FILE` and `HOOK_TAIL_LINES_COMPLETE` in `.claude/hooks/lib/common.sh`.

## Mutation testing: Arcmutate versus exclusions

Pitest 1.30.0 runs through the Gradle plugin in modules named `domain` and `application` (feature 002's
`pitest` convention, `build-logic/src/main/kotlin/pitest.gradle.kts`, which also honours
`-Pharness.mutation.classes=<globs>` for the incremental run). The open-source Kotlin plugin for Pitest is archived; the maintained one is
Arcmutate's, which is commercial. The default is therefore the exclusions fallback: Pitest without the plugin,
excluding Kotlin-synthetic classes and methods (`*$$serializer`, `*$Companion`, `*$WhenMappings`,
`*$DefaultImpls`, generated `equals`, `hashCode`, `toString`, `copy`, `componentN`) and
`avoidCallsTo` for `kotlin.jvm.internal` and `kotlinx.coroutines`, with the 80 % threshold on those packages.
`harness.pitest.arcmutate=false` in `gradle.properties` records that choice; `-Pharness.pitest.arcmutate=true`
adds `com.arcmutate:pitest-kotlin-plugin` (catalogue entry `arcmutate-pitest-kotlin-plugin`) to the Pitest
classpath, which also needs an `arcmutate-licence.txt` in the repository root. Arcmutate licences are paid,
tied to packages and time-limited, and none exists for this public repository (outcome recorded in
specs/001-harness-quality-gates/research.md), so CI never sets it.
Stryker for the TypeScript frontend, once deferred, is active as described above (end-of-task and pull-request
gates).

## Pull-request runner safeguards

From specs/004-ecommerce-platform-mvp/research.md section 4 (the runner executes pull-request code): an
ephemeral (just-in-time) runner for every job; approval required for all outside collaborators; no secrets in
the runner environment; third-party actions pinned by commit SHA; `pull_request` triggers only.

## Branch protection

Require exactly one status check, `pr-gate`, on the protected branch (feature 003 configures it, for example
`scripts/bootstrap-repo.sh --require-check pr-gate`); the jobs behind it, including `verify / verify`, are not
listed individually. A required check that never reports blocks merging, and a skipped one counts as passed,
which is why `pr-gate` runs with `if: always()` and fails when a needed job was skipped (fork pull requests).

## Measuring the success criteria

- **SC-001 and SC-002**: every gate run appends `<ISO time> gate=<name> seconds=<N> status=<pass|fail|timeout|skip>`
  to `.claude/.cache/gate-timing.log`. Take the `gate=post-edit` rows for the per-file gate (90th percentile
  under 60 s) and the `gate=stop` rows with `status=pass` for the complete gate (typically under 15 minutes),
  and `status=skip` for the unchanged case (under 2 s). For example
  `grep gate=post-edit .claude/.cache/gate-timing.log | sed 's/.*seconds=\([0-9]*\).*/\1/' | sort -n`.
- **Measured on 2026-10-02** (feature 002's reference service `services/catalog`, macOS, Apple silicon, warm
  Gradle daemon and caches; rows from `.claude/.cache/gate-timing.log`): per-file gate over 12 edits (4
  `domain`, 3 `application`, 4 `infrastructure` files, one `build.gradle.kts`) took 46 to 50 s for Kotlin
  sources and 1 s for the build script; 90th percentile 49 s, inside the 60 s target of SC-001 with little
  headroom (most of it is the Gradle round trip of ktlint, detekt and the covering test class). Complete gate:
  18 s with `verify` mostly up to date (after a touched `domain` file); a `./gradlew -q verify` that rebuilt the
  conventions and re-ran the build-logic TestKit suite took 121 s; both far inside the 15-minute target of
  SC-002. The repeated run with nothing changed exited in 0 s (`status=skip`, under the 2 s target). Re-measure
  when services are added: the per-file figure is the one to watch.
- **SC-007**: build and test output must stay under 10 % of the context of an agent session. Compare the
  output saved by `rtk gain -p` (project filter, `--history` for the commands) and the session cost view of
  `tokensave cost`, before and after a session of edits; hook output only appears on failures, so a healthy
  session shows none of it.

## Tests

`.claude/hooks/tests/run-all.sh` runs every `test-*.sh` against the fixture monorepo in
`.claude/hooks/tests/fixtures/` with fake `gradlew`, `npm` and `npx` tools; `.github/scripts/tests/run-all.sh`
runs the pull-request gate scripts against fixture Pitest reports, diffs and descriptions in
`.github/scripts/tests/fixtures/` and checks the two workflows statically. Both are silent on success and print
the names of failing tests (`VERBOSE=1` adds their output); the `hook-tests` job runs both on every pull request.
The Pitest convention's two properties are covered by the build-logic TestKit suite (`PitestPluginTest`).

## Shell linting

All hook, test and gate scripts pass `shellcheck -S warning`. Directives in use: `disable=SC2034` at file
level in the two sourced libraries (`.claude/hooks/lib/common.sh`, `scripts/lib/common.sh`), whose variables
are read by the scripts that source them, plus a few line-level `SC2034`/`SC2046` directives where a variable
is read by a helper in another file or word splitting is intentional. Run:
`shellcheck -S warning .claude/hooks/*.sh .claude/hooks/lib/*.sh .claude/hooks/tests/*.sh .github/scripts/*.sh`.
