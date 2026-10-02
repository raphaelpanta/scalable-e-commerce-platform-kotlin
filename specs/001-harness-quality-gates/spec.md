# Feature Specification: Developer Harness Hooks and Quality Gates

**Feature Branch**: `001-harness-quality-gates`

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description: "developer harness hooks and quality gates"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Immediate feedback on every file change (Priority: P1)

A developer or AI coding agent edits a source file. Without asking for it, the harness checks
that file against the project's style rules and runs only the tests that cover it. If everything
passes, nothing is shown. If something fails, the author receives a short, precise diagnosis and
cannot move on until it is fixed.

**Why this priority**: This is the tightest feedback loop and the one that catches most defects
at the lowest cost. It is the behaviour the constitution calls the "per-file hook gate" and the
rest of the harness builds on it.

**Independent Test**: Edit a source file so that it violates a style rule or breaks a covering
test, save it, and observe that the harness reports the failure and blocks progress. Fix the file
and observe that the harness is silent.

**Acceptance Scenarios**:

1. **Given** a module with style rules and tests configured, **When** a source file in that module
   is changed and passes all checks, **Then** the author sees no additional output and work
   continues.
2. **Given** a module with tests configured, **When** a source file is changed and one of its
   covering tests fails, **Then** the author sees which check failed, the command that ran, and no
   more than the last 60 lines of its output, and the current step is blocked until fixed.
3. **Given** a source file whose covering tests cannot be identified by name, **When** it is
   changed, **Then** only the style checks run and the author is not blocked by a "no tests
   found" error.
4. **Given** a file under a generated, dependency or tooling directory, **When** it is changed,
   **Then** the harness does nothing.
5. **Given** a build-definition file, **When** it is changed, **Then** the harness verifies the
   build definition still loads, without running the test suite.

---

### User Story 2 - Full quality gate at the end of a task (Priority: P1)

When a unit of work is declared finished, the harness runs the complete quality gate for the
whole repository: all style checks, all test layers, mutation testing on changed code, and the
frontend checks. The task is only considered done when the gate is green.

**Why this priority**: Per-file checks cannot detect cross-module regressions or contract breaks.
This gate is the constitution's "end-of-task hook gate" and the last line of defence before a
change leaves the developer's machine.

**Independent Test**: Introduce a regression in one module that is only detected by a test in
another module, declare the task finished, and observe that the gate blocks completion with a
concise report. Repair the regression and observe the gate pass and completion proceed.

**Acceptance Scenarios**:

1. **Given** sources changed since the last green gate, **When** the task is declared finished,
   **Then** the complete gate runs and completion is blocked while any check fails.
2. **Given** no relevant sources changed since the last green gate, **When** the task is declared
   finished, **Then** the gate is skipped and completion is immediate.
3. **Given** the gate already blocked completion once in the same cycle and failures remain,
   **When** completion is attempted again, **Then** the remaining failures are reported but the
   author is not trapped in an endless block.
4. **Given** a repository with no build configured yet, **When** a task is declared finished,
   **Then** the gate does nothing and reports nothing.
5. **Given** a frontend package that defines lint and test commands, **When** the gate runs,
   **Then** those commands run in non-interactive mode and never wait for user input.

---

### User Story 3 - Pull-request gate enforced centrally (Priority: P2)

A reviewer opening a pull request can rely on an automated, central run of the same quality gate,
extended with a full mutation-testing run. The pull request cannot be merged while any check
fails, the mutation score of any module is below its declared threshold, or a mutant survives on
changed lines without a recorded justification.

**Why this priority**: Local gates can be bypassed or run on an inconsistent machine. The central
gate is the enforceable version of the same rules and what the constitution's "pull request gate"
describes.

**Independent Test**: Open a pull request that lowers a module's mutation score below its
threshold and observe that the pull request is marked as failing with the module and score named.

**Acceptance Scenarios**:

1. **Given** a pull request, **When** the central gate runs, **Then** it executes every check the
   local end-of-task gate executes plus a full (non-incremental) mutation run.
2. **Given** a module whose mutation score drops below its declared threshold, **When** the
   central gate runs, **Then** the pull request is marked failing and the report names the module,
   the threshold and the achieved score.
3. **Given** a surviving mutant on a line changed in the pull request, **When** the central gate
   runs, **Then** the pull request is marked failing unless the pull request records a
   justification for that mutant.
4. **Given** all checks pass, **When** the central gate completes, **Then** the pull request shows
   a single consolidated status that reviewers can read in under a minute.

---

### User Story 4 - Quiet, token-efficient output (Priority: P2)

Every check run by the harness is silent on success and concise on failure. Build and test
tooling is configured so that only errors and failures reach the author or agent, keeping human
attention and AI context budget for what matters.

**Why this priority**: The constitution makes token economy a first-class principle. Verbose
build logs are the single largest source of wasted context in agent-assisted development.

**Independent Test**: Run a passing full gate and confirm zero lines of output; run a failing one
and confirm the output contains only the failing check's identity and a bounded excerpt.

**Acceptance Scenarios**:

1. **Given** any check that passes, **When** it completes, **Then** it contributes no output to
   the author's or agent's view.
2. **Given** any check that fails, **When** it completes, **Then** the output names the failing
   check, shows the exact command that ran, and includes at most a fixed, documented number of
   trailing lines from its log.
3. **Given** the build or package tooling runs outside the harness, **When** invoked by the
   agent, **Then** it runs with the project's quiet defaults and shows progress or lifecycle
   noise only when an error occurs.

---

### User Story 5 - Harness is documented and controllable (Priority: P3)

A developer joining the project can read how the harness works, run any gate manually, perform a
dry run that prints what would be executed, and temporarily bypass a gate when there is a
legitimate reason, with the bypass visible to reviewers.

**Why this priority**: A harness nobody understands gets disabled. Documentation and explicit
escape hatches keep the gates trusted and in use.

**Independent Test**: Follow the documentation to run the end-of-task gate manually in dry-run
mode and confirm it prints the commands it would run without executing them.

**Acceptance Scenarios**:

1. **Given** a new developer, **When** they read the project's contributor documentation,
   **Then** they can list every gate, what triggers it, what it checks and how to run it by hand.
2. **Given** any gate, **When** it is run in dry-run mode, **Then** it prints the commands it
   would execute and exits without running them.
3. **Given** a developer with a legitimate reason to skip a gate, **When** they bypass it, **Then**
   the bypass is explicit, scoped to a single run, and leaves a trace reviewers can see.

---

### Edge Cases

- A file is changed outside the repository root: the harness ignores it.
- A required checking tool is not installed or not yet configured in the build: the harness
  skips that check silently rather than failing with a tooling error.
- A check exceeds its time budget: the harness aborts it, reports the timeout as a failure of
  that check, and does not leave background processes running.
- Two file changes trigger checks concurrently: each result is reported independently and no
  check corrupts another's state.
- A test runner defaults to watch or interactive mode: the harness forces non-interactive mode so
  the gate always terminates.
- The last-green marker is deleted or absent: the gate runs in full.
- A module declares no mutation threshold: the gate applies the constitution's default minimum.
- Mutation testing on changed code finds no mutants (for example, only comments changed): the
  gate passes that check without error.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The harness MUST run style checks and the covering tests for a source file
  automatically whenever that file is created or modified by a developer or agent.
- **FR-002**: The harness MUST identify covering tests by the project's naming conventions and
  MUST skip the test step, not fail, when none can be identified.
- **FR-003**: The harness MUST run the complete repository quality gate automatically when a unit
  of work is declared finished.
- **FR-004**: The complete gate MUST include: all style checks, all test layers defined by the
  constitution, mutation testing on code changed since the last green run, and every frontend
  package's lint and test commands.
- **FR-005**: The harness MUST skip the complete gate when no relevant source has changed since
  the last green run, and MUST record a new last-green marker after each passing run.
- **FR-006**: Any gate that fails MUST block the current step and report, at minimum: the name of
  the failing check, the exact command executed, and a bounded excerpt (at most 60 lines) of its
  output.
- **FR-007**: Any gate that passes MUST produce no output visible to the author or agent.
- **FR-008**: The harness MUST NOT block completion more than once per completion attempt cycle;
  on a repeated failure it MUST report without blocking.
- **FR-009**: The harness MUST ignore changes under generated, dependency, build-output and
  tooling directories, and changes outside the repository.
- **FR-010**: The harness MUST do nothing, silently, in a repository where the relevant build or
  package tooling is not yet present.
- **FR-011**: The harness MUST run all test and mutation tooling in non-interactive mode so every
  gate terminates without user input.
- **FR-012**: Each gate MUST have a declared time budget; a check exceeding it MUST be aborted and
  reported as a failure.
- **FR-013**: Build and package tooling used by the project MUST be configured with quiet
  defaults so that lifecycle and progress output is suppressed and only errors, warnings that
  fail the build, and test failures are shown.
- **FR-014**: A central pull-request gate MUST run every check of the complete local gate plus a
  full mutation run, and MUST mark the pull request failing on any failure.
- **FR-015**: The pull-request gate MUST enforce a per-module mutation-score threshold, defaulting
  to the constitution's minimum of 80%, and MUST fail when a module's score is below its threshold
  or has decreased relative to the target branch.
- **FR-016**: The pull-request gate MUST fail when a mutant survives on a changed line unless a
  justification for that mutant is recorded in the pull request.
- **FR-017**: Every gate MUST support a dry-run mode that prints the commands it would execute
  without running them.
- **FR-018**: Every gate MUST be runnable manually by a developer with a single documented
  command.
- **FR-019**: Bypassing a gate MUST be explicit, scoped to one run, and leave a visible trace for
  reviewers.
- **FR-020**: The project MUST include contributor documentation listing each gate, its trigger,
  its checks, its time budget, and how to run, dry-run and bypass it.

### Key Entities

- **Gate**: A named set of checks bound to a trigger (file change, task completion, pull request)
  with a time budget and a blocking policy.
- **Check**: A single verification (style, test, mutation, frontend command) with an identity, a
  command, an outcome and a bounded output excerpt.
- **Check Result**: The outcome of one check: pass, fail or skipped, plus the excerpt shown on
  failure.
- **Last-Green Marker**: The record of the most recent fully passing complete gate, used to decide
  whether the gate can be skipped and which code counts as changed.
- **Mutation Threshold**: The minimum acceptable mutation score declared per module, with the
  constitution's default applied when none is declared.
- **Bypass Record**: The visible trace left when a developer deliberately skips a gate for one run.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After a single-file change in a typical module, the author receives pass/fail
  feedback within 60 seconds in at least 90% of cases.
- **SC-002**: The complete gate finishes within 15 minutes for the full repository on a standard
  developer machine, and within 2 seconds when nothing has changed since the last green run.
- **SC-003**: 100% of failing checks are reported with the check name, the command run and an
  excerpt of at most 60 lines; passing checks add zero lines of output.
- **SC-004**: No completion attempt is ever blocked more than once in a row by the same gate, as
  verified by a test that simulates a persistently failing check.
- **SC-005**: Every pull request shows a single consolidated gate status; no pull request merges
  with a failing check or a module below its mutation threshold.
- **SC-006**: Developers can perform a dry run of any gate and read the complete list of commands
  it would run in under one minute, following only the contributor documentation.
- **SC-007**: In agent-assisted sessions, build and test output accounts for less than 10% of the
  context consumed by tool results over a working day, measured by the token-saving tooling's
  reports.

## Assumptions

- A baseline of the per-file and end-of-task gates already exists in the project's agent
  configuration; this feature formalises its behaviour, adds the missing pieces (central gate,
  mutation thresholds, dry-run and bypass conventions, documentation) and brings the build tooling
  defaults in line with the constitution.
- The repository's build and frontend tooling will be introduced by the separate build
  bootstrap feature; until then the gates are silent no-ops.
- The constitution's defaults apply where this spec is silent: mutation threshold of 80% that
  may not decrease, the constitution's style rules, the four test layers, and quiet logging.
- A hosted source-control service with pull requests and a continuous-integration runner will be
  available for the central gate; the specific provider is decided at planning time.
- "Changed code" for incremental mutation testing means files modified since the last-green
  marker locally, and files differing from the target branch in a pull request.
- Time budgets default to 5 minutes for the per-file gate and 20 minutes for the complete gate
  unless the plan sets different values.
- Justifications for surviving mutants are recorded in the pull-request description or an
  equivalent reviewable artifact, not in code comments.
