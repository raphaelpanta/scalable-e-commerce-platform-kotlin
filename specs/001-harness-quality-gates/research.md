# Research: Developer Harness Hooks and Quality Gates

**Feature**: 001-harness-quality-gates | **Date**: 2026-10-02

All decisions below are settled; no clarification items remain. The baseline already in the
repository (`.claude/settings.json`, `.claude/hooks/post-edit-check.sh`,
`.claude/hooks/stop-full-check.sh`) implements D1, D2 (name-based discovery), D3 (marker and loop
guard), `HOOK_DRY_RUN` and silent success; this feature extends it. Version inputs come from
specs/004-ecommerce-platform-mvp/research.md sections 4, 13 and 14 (JDK 25, Gradle 9.8, Kotlin
managed by Spring Boot 4.1, Kotest 6.2.5, Pitest 1.30.0 with `info.solidsoft.pitest` 1.19.0,
Pact JVM 4.7.5, Cucumber 7.34.x, StrykerJS 10).

## D1. Hook events used

- **Decision**: Two Claude Code hook events, both declared in `.claude/settings.json` and
  implemented as bash scripts in `.claude/hooks/`: `PostToolUse` with matcher `Write|Edit` runs
  `post-edit-check.sh` (per-file gate); `Stop` runs `stop-full-check.sh` (end-of-task gate).
  Failure is signalled with exit code 2 and a short message on stderr, which Claude Code feeds back
  to the agent so the step is blocked until fixed. Settings timeouts are set slightly above the
  internal budgets (330 s and 1230 s) so the script's own timeout and report fire first.
- **Rationale**: These are the two events that map one-to-one to constitution Quality Gates 1 and
  2. `PostToolUse` fires after the file exists on disk (the check sees the new content);
  `Stop` is the only event that fires when the agent declares work finished and supports blocking.
  Bash starts in milliseconds, which matters for SC-001.
- **Alternatives considered**: `PreToolUse` (file not yet written, cannot lint it); `SubagentStop`
  and `SessionEnd` (not the completion point of the main task; `SessionEnd` cannot block); git
  pre-commit hooks (outside agent loop, easy to bypass with `--no-verify`, no feedback during
  work); a Claude Code plugin (extra packaging layer, hides behaviour; see plan Complexity
  Tracking).

## D2. Targeted-test discovery strategy

- **Decision**: Name-based discovery, no build-graph analysis. For an edited `Foo.kt` under module
  `M` (nearest ancestor with `build.gradle.kts`), search `M/src/*[tT]est*/` for files named
  `Foo*.kt` (covers `FooTest`, `FooSpec`, `FooPropertyTest`, `FooIntegrationTest`), cap at 20, and
  run `gradle :M:test --tests '*.<TestClass>'` for each. If the edited file is itself a test, run
  that class. If no covering test is found, run only the style tasks (`ktlintCheck`, `detekt`,
  present only when configured in the build) and never fail on "no tests found". For TypeScript:
  `eslint` on the file, `tsc --noEmit` for the package, `vitest related <file> --run
  --passWithNoTests`, plus `prettier --check <file>` when a Prettier config exists. `*.gradle.kts`
  edits run `gradle help` (script compilation). Files under `build/`, `node_modules/`, `.gradle/`,
  `dist/`, `.claude/`, `.specify/`, or outside the repo root are ignored; missing tools (no
  `gradlew`, no `ktlint` in the build, no `package.json`) cause a silent exit 0.
- **Rationale**: Project conventions already name tests after the class under test (Principle V,
  Kotest specs), so a glob is cheap, deterministic and fast; it avoids a compile-time dependency
  graph walk that would cost seconds per edit. Falling back to style-only keeps FR-002.
- **Alternatives considered**: Gradle test-impact analysis plugins (heavy, not maintained for
  Kotlin 2.x/Gradle 9); running the whole module's tests (violates the 60 s target for large
  modules); coverage-map based selection (needs a previous instrumented run, fragile); Pitest's
  own history for tests (only relevant to mutation, see D5).

## D3. Last-green marker and loop guard

- **Decision**: The marker is the mtime of `.claude/.cache/last-full-check`, git-ignored. After a
  passing complete gate it is replaced atomically (write temp, `mv`). "Changed since last green" is
  `find -newer` over relevant extensions (`.kt .kts .ts .tsx .toml .json .feature .properties .yml
  .yaml .sql`), pruning `build`, `node_modules`, `.gradle`, `.claude`, `.specify`, `dist`; the same
  file list feeds the incremental mutation step (D5). A missing marker means a full run. A passing
  skip costs one `find ... -quit` and must complete within 2 s (SC-002). On failure the marker is
  not touched. The loop guard uses the `stop_hook_active` field of the Stop-hook input: when false
  the gate blocks (exit 2 with the report on stderr); when true (the agent already got one block in
  this cycle) the gate emits the same report as a JSON `systemMessage` and exits 0, so the author is
  informed but never trapped (FR-008, SC-004). The per-file gate has no loop guard because it fires
  once per edit and each edit is a deliberate fix attempt.
- **Rationale**: mtime markers need no state format, survive crashes, and are trivially testable
  by `touch -t`. `stop_hook_active` is the protocol-provided signal for exactly this situation.
- **Alternatives considered**: git commit hash marker (no git repo guaranteed locally, ignores
  uncommitted work); content-hash manifest (more precise but slower to compute and to test);
  own block counter file (duplicates the protocol signal and can desynchronise from it).

## D4. Bypass mechanism

- **Decision**: `HOOK_BYPASS=1` in the environment of a single invocation makes either hook (and
  `run-gate.sh`) skip all checks, exit 0, and append one line to `.claude/.cache/bypass.log`:
  `<ISO-8601 UTC> gate=<post-edit|stop|pr-local> user=<$USER> file=<path or -> reason=<HOOK_BYPASS_REASON or ->`.
  Only the exact value `1` bypasses. The variable is never written to `settings.json`, never
  exported by the hooks, and is ignored when `GITHUB_ACTIONS` or `CI` is `true` (the PR gate cannot
  be bypassed). To keep the bypass visible to reviewers despite the log being local and
  git-ignored, the PR template (feature 003) gets a checklist item "Gate bypasses: `none`, or the
  lines from `.claude/.cache/bypass.log` pasted below", and `pr-gate.yml` fails if the PR body
  lacks that checklist section. Dry-run (`HOOK_DRY_RUN=1`) is evaluated before bypass so a dry run
  still lists commands.
- **Rationale**: An environment variable is explicit, scoped to the process that receives it, and
  leaves no persistent switch to forget. A log line plus a PR checklist makes the trace reviewable
  without any server component.
- **Alternatives considered**: a `.claude/.bypass` flag file (persists across runs, easy to
  forget); a commit-message trailer (does not apply to agent edits); editing `settings.json`
  (persistent, noisy diffs); `--no-verify` (not applicable to Claude hooks, invisible).

## D5. Mutation tooling: Pitest with Arcmutate versus exclusions fallback; Stryker deferred

- **Decision**: JVM modules use Pitest 1.30.0 through the Gradle plugin `info.solidsoft.pitest`
  1.19.0, applied by the convention plugin `harness.pitest-conventions` in `build-logic/` to
  modules named `domain` and `application` only (Constitution VIII minimum). The convention sets:
  `mutationThreshold` (default 80, overridable per module through a `harnessMutation { threshold }`
  extension), `failWhenNoMutations = false` (covers the "only comments changed" edge case),
  JUnit 5 platform (Kotest runner) via the pitest-junit5 plugin, XML plus HTML output with
  `timestampedReports = false` (the XML feeds the surviving-mutant check), history files for
  incremental analysis, quiet logging, and a `targetClasses` override read from the project property
  `harness.mutation.classes` (comma-separated globs). **Kotlin support**: the open-source
  `pitest-kotlin` plugin is archived; the maintained option is Arcmutate's
  `com.arcmutate:pitest-kotlin-plugin`, which is commercial. Default is the exclusions fallback:
  Pitest without the plugin plus excluded classes/methods for Kotlin-synthetic code
  (`*$$serializer`, `*$Companion`, `*$WhenMappings`, `*$DefaultImpls`, `*$lambda*`, generated data
  class members `equals/hashCode/toString/copy/componentN`) and `avoidCallsTo` for
  `kotlin.jvm.internal` and `kotlinx.coroutines`; the 80 % threshold stays on the domain/application
  packages and the exclusions are documented in `docs/harness.md`. A single Gradle property
  `harness.pitest.arcmutate=true` (default false) adds the Arcmutate artifact to the `pitest`
  configuration if a licence for this public MIT repository is obtained; that outcome is recorded as
  a task, and flipping the property is the only change needed. **Local gate**: incremental, only
  classes derived from changed `domain`/`application` `.kt` files since the marker
  (`harness.mutation.classes=pkg.Foo*`) plus history reuse. **CI**: full run, no class filter, no
  history. **Stryker**: StrykerJS 10 is wired conditionally in the Stop hook and in `verify.yml`
  (runs only when a `stryker.config.*` exists in a package, with `--incremental`); no frontend
  exists yet so it is inert and covered only by fixture tests.
- **Rationale**: Native Pitest threshold fails the module's build with the module and score in the
  message (FR-015 first half) without custom code; XML is the stable machine-readable source for
  survivors. A property switch isolates the licensing uncertainty from the rest of the work.
- **Alternatives considered**: PIT without any Kotlin handling (noise from synthetic mutants
  lowers real scores); Arcmutate as a hard requirement (blocked by licence status); other Kotlin
  mutation tools (none maintained at comparable maturity); Stryker for JVM (not applicable);
  line coverage thresholds (forbidden as a merge criterion by Principle V).

## D6. Pull-request gate composition

- **Decision**: `.github/workflows/pr-gate.yml`, triggered by `pull_request` (types opened,
  synchronize, reopened) on the containerised self-hosted runner, with `permissions: contents:
  read`, actions pinned by commit SHA, `concurrency` cancelling superseded runs, and the safeguards
  of 004 §4 (ephemeral runner, approval for outside collaborators, no secrets). Jobs: (1) `verify`
  calls the reusable `.github/workflows/verify.yml`, which runs `./gradlew -q check` and, when a
  `package.json` exists, frontend `lint` and `test` with the package manager detected from the
  lockfile; feature 002's CI requirement reuses the same workflow; (2) `mutation` runs
  `./gradlew -q pitest` (full, no class filter) then `.github/scripts/mutation-gate.sh` (per-module
  score vs declared threshold, default 80, and vs `quality/mutation-baseline.json`, which may only
  increase and is protected by comparing against the base branch copy) and
  `.github/scripts/surviving-mutants.sh` (survivors in the Pitest XML intersected with
  `git diff -U0 <base>...HEAD` added lines; each must be justified in a `## Mutant justifications`
  section of the PR description as `path:line reason`, read from the event payload without network
  access); (3) `pr-gate` (the single required status check for branch protection in feature 003)
  needs the other jobs and writes a summary of at most 20 lines to `$GITHUB_STEP_SUMMARY`; it also
  checks the PR body contains the bypass checklist section (D4). `pr-gate.sh` runs the same steps in
  the same order and is what `run-gate.sh pr` calls locally, so the local and central gates do not
  drift. Frontend Stryker runs in the `mutation` job only when configured.
- **Rationale**: One aggregate required check satisfies "single consolidated status" (SC-005);
  separating `verify` lets feature 002 and this feature share it; reading survivors from XML and
  the diff keeps the logic provider-agnostic and unit-testable with fixtures.
- **Alternatives considered**: one monolithic job (no reuse, slower to diagnose); per-service path
  filtered workflows only (feature 004 has those for images; the gate must also see cross-module
  effects); GitHub-hosted runners (declined by the requester; kept as the fallback in 004 §4);
  a SaaS mutation dashboard (new account and cost, offline-unfriendly); `pull_request_target`
  (exposes secrets to forked code; rejected).

## D7. Time budgets

- **Decision**: Per-file gate 300 s total, complete gate 1200 s total (spec default; documented,
  overridable through `HOOK_BUDGET_SECONDS` for tests). Each check runs through `run_budgeted` in
  `lib/common.sh`, which uses `timeout -k 5` (or `gtimeout`, else a bash watchdog that kills the
  child process tree), receives the remaining time of the gate's deadline rather than a fresh
  allowance, and on expiry is reported as a failed check named `<check>: TIMEOUT after <N>s` with
  the usual command and tail. The Gradle client is terminated; the Gradle daemon is intentionally
  left to idle out as designed and is the only process allowed to survive. The CI workflow sets
  `timeout-minutes: 30` for the whole job. SC-001/SC-002 targets (60 s at the 90th percentile; 15
  min typical for the complete gate) are targets inside these ceilings; the 20-minute budget is the
  abort point. Each run appends a line to `.claude/.cache/gate-timing.log` for measurement.
- **Rationale**: A budget enforced inside the script yields a readable failure; relying only on the
  `settings.json` timeout would kill the hook with no report. A shared deadline prevents several
  slow checks from exceeding the total.
- **Alternatives considered**: Only `settings.json` timeouts (no report, no cleanup); per-check
  fixed budgets (sum can exceed the gate budget); no budget in CI (a hung runner blocks the queue).

## D8. Keeping hooks silent on success

- **Decision**: Every check's stdout and stderr are redirected to a per-run log created with
  `mktemp` (unique per invocation, so concurrent edits never share state) and deleted by a `trap`.
  Nothing is echoed on success; the scripts exit 0 with zero bytes on stdout and stderr (except
  `HOOK_DRY_RUN=1`, whose purpose is printing). On failure one report per failed check:
  `<gate> check FAILED: <check name>`, `$ (cd <dir> && <exact command>)`, then the last 60 lines of
  the log (40 per check in the complete gate, still within the FR-006 maximum; the constants are
  documented in `docs/harness.md`), with an `(N earlier lines omitted)` marker. Tools run with
  quiet defaults: `-q --console=plain` and `gradle.properties` (`org.gradle.console=plain`,
  `org.gradle.logging.level=quiet`, `org.gradle.warning.mode=none`), a `harness.test-logging-
  conventions` plugin (events `FAILED` only, `exceptionFormat = FULL`, `showStandardStreams =
  false`), `NPM_CONFIG_LOGLEVEL=error` and `.npmrc`, `CI=1` and `NO_COLOR=1` (already in
  `settings.json` env) so test runners never enter watch mode. A test asserts zero output bytes for
  every passing scenario (SC-003), and SC-007 is measured from the token-saving tool's reports
  (`rtk`/`tokensave`) as described in `docs/harness.md`.
- **Rationale**: The only reliable way to guarantee silence is to never let children write to the
  inherited descriptors; bounded tails give the agent enough to act and no more.
- **Alternatives considered**: Filtering output with grep (fragile, may hide the real error);
  printing a one-line "ok" (violates FR-007 and consumes context on every edit); full logs on
  failure (violates FR-006).

## Open items

None. The Arcmutate licence outcome is an implementation task, not a planning unknown: the plan
works in both cases through the `harness.pitest.arcmutate` switch (D5).

## Arcmutate licence outcome

- **Question** (open follow-up of specs/004-ecommerce-platform-mvp/research.md section 14): can this public,
  MIT-licensed repository use Arcmutate's Kotlin plugin for Pitest?
- **Findings (2026-10-02)**: the plugin is published on Maven Central as
  `com.arcmutate:pitest-kotlin-plugin` (release 1.5.1), but running it requires a licence. Arcmutate's
  documentation (docs.arcmutate.com, "Licence Management") describes licences as plain-text files
  (`arcmutate-licence.txt`, read from the repository root or the Pitest reports directory) that are bought
  by subscription, tied to root packages and valid until an expiry date; neither the licence agreement nor the
  documentation offers a free licence for open-source projects. A licence file committed to this public
  repository would be published, and feature 002's CI runner holds no secrets, so it could not be injected
  there either.
- **Outcome**: not adopted. `harness.pitest.arcmutate=false` in `gradle.properties`; the exclusions fallback of
  D5 is the supported configuration, locally and in CI, with the 80 % threshold unchanged.
- **Switch kept**: `-Pharness.pitest.arcmutate=true` adds the catalogue entry `arcmutate-pitest-kotlin-plugin`
  to the `pitest` configuration (covered by `PitestPluginTest`), so a maintainer holding a licence can use it
  locally. Adopting it for the repository means: obtain a licence covering `com.ecommerce`, provide the file to
  CI without committing it (which requires revisiting the no-secrets runner rule of feature 002), set the
  property to `true`, and re-baseline `quality/mutation-baseline.json`, since Kotlin-aware mutants change the
  scores.
