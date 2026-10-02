---

description: "Task list for feature 003: publish the repository publicly on GitHub"
---

# Tasks: Publish Repository Publicly on GitHub

**Input**: Design documents from `/specs/003-github-public-repo/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, quickstart.md. data-model.md and contracts/ are not produced for this feature (see plan.md).

**Tests**: MANDATORY (constitution Principle V and Definition of Done). Shell tests live under `scripts/tests/` in plain Bash (bats is not installed). Tests use a stub `gh` (`scripts/tests/stubs/gh`) and temporary git repositories, so no test touches GitHub. Seeded secrets are assembled at runtime and never stored as literals. Write each test first and confirm it fails before implementing.

**Organization**: Tasks are grouped by user story. US1 = publish, US2 = nothing sensitive published, US3 = collaboration-ready. The single outward-facing, irreversible act (create the repository and push) is isolated in task T045 and needs the maintainer's explicit confirmation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task belongs to (US1, US2, US3); present only in story phases
- Include exact file paths in descriptions

## Path Conventions

- Scripts: `scripts/` (shared helpers in `scripts/lib/`, tests in `scripts/tests/`)
- Community documents: repository root and `.github/`
- Hook: `.githooks/pre-push`
- All paths are relative to the repository root `/Users/raphaelpantaleao/Workspace/scalable-e-commerce-platform-kotlin`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Test harness and shared script helpers

- [X] T001 [P] Create assertion helpers (assert_eq, assert_contains, assert_not_contains, assert_file_exists, assert_exit_code, quiet failure output) in scripts/tests/lib/assert.sh
- [X] T002 [P] Create the test runner that executes every scripts/tests/test_*.sh quietly, prints one `PASS: N tests` summary line and exits non-zero on any failure in scripts/tests/run-all.sh
- [X] T003 [P] Create a stateful fake `gh` (state directory from `GH_STUB_STATE`, records every call in `calls.log`, supports `auth status`, `api user`, repo GET/PATCH/PUT endpoints, `repo create`, `repo view`, and reports write calls distinctly from reads) in scripts/tests/stubs/gh
- [X] T004 [P] Create shared helpers (quiet/verbose logging, `step` reporter, `die`, single `run` mutation wrapper that prints `DRY-RUN` and skips when dry-run is on, `gh_read`/`gh_write` using `${GH_BIN:-gh}`, constants for description and topics) in scripts/lib/common.sh

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Script skeletons and the safety gates every story relies on

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T005 Create the bootstrap script skeleton with `set -euo pipefail`, argument parsing (`--dry-run`, `--yes`, `--owner`, `--name`, `--require-check` repeatable, `--admin-bypass`, `--verbose`, `--help`), defaults (name `scalable-e-commerce-platform-kotlin`, branch `main`), and an ordered step runner in scripts/bootstrap-repo.sh
- [X] T006 Add the read-only preflight (gh signed in, token scopes include `repo`, owner resolved from `gh api user`, repository name not taken by a foreign repository, git user configured) that stops before any local change, and the confirmation gate (type the repository name, or `--yes`; skipped in dry-run) in scripts/bootstrap-repo.sh
- [X] T007 [P] Create the verification script skeleton (`--repo owner/name`, `--verbose`, `REPO_ANON_URL` and `GH_BIN` overrides, `check` helper printing one `OK`/`FAIL` line per check, final `VERIFIED` summary, non-zero exit on any failure) in scripts/verify-repo.sh

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Project lives in a public hosted repository (Priority: P1) MVP

**Goal**: A public repository named after the project exists under the maintainer's account, with description, topics and the complete local content pushed as a single initial history that the local copy tracks.

**Independent Test**: Run `scripts/bootstrap-repo.sh --dry-run` and the stub-based tests; after publication, open the repository signed out and clone it anonymously, then run `scripts/verify-repo.sh` (clone tree equals local `HEAD` tree).

### Tests for User Story 1 (write first, confirm they fail)

- [X] T008 [P] [US1] Write the dry-run test: in a temp copy of the project with the stub `gh`, `--dry-run` creates no `.git`, makes no write call in `calls.log`, prints every mutating step prefixed `DRY-RUN`, is identical on repeat, honours `--owner` and `--name` overrides (reproducibility for a second repository, FR-013), and stops before any change when signed out or the name is taken, in scripts/tests/test_bootstrap_dry_run.sh
- [X] T009 [P] [US1] Write the idempotency test: run the real path twice against the stub `gh` and a local bare remote and assert exactly one commit, one `repo create` call, a no-op second push, and recovery when the first run is interrupted after the commit but before repository creation, in scripts/tests/test_bootstrap_idempotency.sh
- [X] T010 [P] [US1] Write verify-repo tests for the publication checks (anonymous visibility via `REPO_ANON_URL` pointing at a local bare repo, default branch equals local branch, clone tree hash equals local `HEAD` tree, topics and description present, failure when the clone differs or the repository is private) in scripts/tests/test_verify_repo.sh

### Implementation for User Story 1

- [X] T011 [US1] Implement the local git steps (`git init -b main` only if no repository exists, stage everything, create one initial commit only if `HEAD` is absent, never rewrite existing history) in scripts/bootstrap-repo.sh
- [X] T012 [US1] Implement repository creation (`gh repo create <owner>/<name> --public --description ... --source . --remote origin`, skipped when the expected repository already exists, stop if it exists and is not ours) and `git push -u origin main` as a no-op when up to date in scripts/bootstrap-repo.sh
- [X] T013 [US1] Apply the one-line description and the topics `ecommerce microservices kotlin spring-boot kafka hexagonal-architecture ddd` declaratively (`PATCH` repo, `PUT /repos/{owner}/{repo}/topics`) in scripts/bootstrap-repo.sh
- [X] T014 [US1] Implement the publication checks (unauthenticated `curl` and `git ls-remote` with `GIT_TERMINAL_PROMPT=0`, default branch equals local, anonymous clone into a temp directory with `git ls-tree -r HEAD` hash compared to local, description and topics present) in scripts/verify-repo.sh
- [X] T015 [US1] Invoke `scripts/verify-repo.sh` at the end of bootstrap (skippable in dry-run), print the final summary, and handle an accidentally private repository by printing or applying `gh repo edit --visibility public --accept-visibility-change-consequences` under `--yes` in scripts/bootstrap-repo.sh

**Checkpoint**: User Story 1 is testable offline (T008-T010 pass); live publication happens in T045

---

## Phase 4: User Story 2 - Nothing sensitive or temporary is published (Priority: P1)

**Goal**: No secret, credential, local cache, local-only setting or review scratch leaves the machine, now or on any later push.

**Independent Test**: `scripts/tests/test_pre_push_hook.sh` blocks a runtime-generated seeded secret naming file and line; `scripts/tests/test_ignore_rules.sh` and `scripts/tests/test_constitution_clean.sh` pass; after publication `scripts/verify-repo.sh` reports no ignored paths and secret scanning plus push protection enabled.

### Tests for User Story 2 (write first, confirm they fail)

- [X] T016 [P] [US2] Write the ignore-rule test: in a temp repo using the real `.gitignore`, `git check-ignore` matches `.claude/.cache/`, `.claude/settings.local.json`, `.tokensave/`, `.rtk/`, `build/`, `.gradle/`, `node_modules/`, `.idea/`, `.DS_Store`, `.env`, `*.pem`, and does not match `.env.example`, `specs/`, `.specify/memory/constitution.md`, in scripts/tests/test_ignore_rules.sh
- [X] T017 [P] [US2] Write the constitution test: the real `.specify/memory/constitution.md` has no `Sync Impact Report` text and no HTML comment before its first heading, and the guarded removal routine strips the comment from a fixture while leaving an unrelated comment untouched, in scripts/tests/test_constitution_clean.sh
- [X] T018 [P] [US2] Write the seeded-secret hook test: temp repo plus bare remote with `core.hooksPath` set to the real `.githooks`, a GitHub-token-shaped value generated at runtime is blocked (non-zero exit, output names the file and line, value redacted), a clean push passes, a brand-new branch scans full history, and a `PATH` without `gitleaks` fails closed with install instructions, in scripts/tests/test_pre_push_hook.sh
- [X] T019 [P] [US2] Extend the verify tests with the cleanliness cases (a clone containing an ignored path or `.claude/settings.local.json` fails, secret scanning or push protection reported disabled fails, open secret-scanning alerts non-zero fails) in scripts/tests/test_verify_repo.sh

### Implementation for User Story 2

- [X] T020 [P] [US2] Create the ignore rules (build outputs, `.gradle/`, `node_modules/`, IDE files, `.claude/.cache/`, `.claude/settings.local.json`, `.tokensave/`, `.rtk/`, `.DS_Store`, `*.log`, `*.tmp`, `.env`, `.env.*` except `.env.example`, `*.pem`, `*.key`) in .gitignore
- [X] T021 [P] [US2] Create the gitleaks configuration (`[extend] useDefault = true`, allowlist limited to `scripts/tests/` fixtures that contain no literal secrets, redaction on) in .gitleaks.toml
- [X] T022 [P] [US2] Create the local pre-push gate (reads ref ranges from stdin, scans the pushed range or full history for a new branch with `gitleaks git --redact --config .gitleaks.toml`, fails closed when `gitleaks` is missing, prints remediation) and mark it executable in .githooks/pre-push
- [X] T023 [P] [US2] Remove the temporary Sync Impact Report HTML comment (everything from `<!--` through `-->` before the first heading) from .specify/memory/constitution.md, leaving the document body and version line `1.1.0` unchanged (FR-003)
- [X] T024 [US2] Add the publication safeguards to bootstrap: ensure `gitleaks` (Homebrew install, skipped in dry-run), refuse to continue if the constitution still contains the Sync Impact Report comment, run `git config core.hooksPath .githooks`, unstage any ignored staged path (`git ls-files -ci --exclude-standard` then `git rm --cached`), and abort on any gitleaks finding before the first commit, in scripts/bootstrap-repo.sh
- [X] T025 [US2] Enable GitHub secret scanning and push protection (`PATCH` repo with `security_and_analysis`) and Dependabot vulnerability alerts (`PUT /repos/{owner}/{repo}/vulnerability-alerts`) in scripts/bootstrap-repo.sh
- [X] T026 [US2] Implement the cleanliness checks (no tracked path in the anonymous clone matches the ignore rules, forbidden local-only paths and the Sync Impact Report text absent, secret scanning and push protection `enabled`, zero open secret-scanning alerts) in scripts/verify-repo.sh

**Checkpoint**: User Stories 1 and 2 work together; nothing sensitive can be committed or pushed

---

## Phase 5: User Story 3 - Repository is ready for public collaboration (Priority: P2)

**Goal**: A visitor finds the overview, licence, contribution guide, code of conduct and security policy; a contributor sees the PR gate template; `main` is protected and merges are squash-only.

**Independent Test**: `scripts/tests/test_community_files.sh` passes; after publication, a signed-out visitor locates the files within 2 minutes and a direct push to `main` is rejected (quickstart sections 6 and 7).

### Tests for User Story 3 (write first, confirm they fail)

- [X] T027 [P] [US3] Write the community-files test (all files present at recognised paths; LICENSE is MIT with year 2026 and the maintainer from `git config user.name`; README has purpose, architecture summary, build/verify and links to `.specify/memory/constitution.md` and `specs/`; SECURITY.md states 5 business days and private reporting; CODE_OF_CONDUCT.md is Contributor Covenant 2.1; PULL_REQUEST_TEMPLATE.md has the five gate items; CODEOWNERS has a catch-all entry; CONTRIBUTING.md documents `core.hooksPath` and squash merge) in scripts/tests/test_community_files.sh
- [X] T028 [P] [US3] Extend the idempotency test to assert the recorded protection payload (1 approval, dismiss stale reviews, conversation resolution, linear history, no force push, no deletion, enforce admins, required checks only with `--require-check`), squash-only merge settings, private vulnerability reporting and Actions hardening calls, and that a second run converges without drift, in scripts/tests/test_bootstrap_idempotency.sh
- [X] T029 [P] [US3] Extend the verify tests with the collaboration cases (licence not MIT fails, protection field mismatch fails, merge commit allowed fails, private vulnerability reporting disabled fails, community profile below 100 fails) in scripts/tests/test_verify_repo.sh

### Implementation for User Story 3

- [X] T030 [P] [US3] Create the MIT licence text with `Copyright (c) 2026` and the maintainer's name from `git config user.name` in LICENSE
- [X] T031 [P] [US3] Write the overview (purpose, architecture summary, build and verify commands, how this repository was published and how to reproduce it with `scripts/bootstrap-repo.sh`, links to `.specify/memory/constitution.md` and `specs/`, licence badge) in README.md
- [X] T032 [P] [US3] Write the contribution guide (activate the hook with `git config core.hooksPath .githooks` and install `gitleaks`, branch and pull-request flow, squash-only merges, constitution gates, workflow-safety rules for the self-hosted runner: no `pull_request_target` with untrusted checkout and SHA-pinned actions, leaked-secret remediation steps, contributions accepted under MIT) in CONTRIBUTING.md
- [X] T033 [P] [US3] Add Contributor Covenant 2.1 with the maintainer's contact method for conduct reports in CODE_OF_CONDUCT.md
- [X] T034 [P] [US3] Write the security policy (private vulnerability reporting via the Security tab, first response within 5 business days, supported versions, no public issues for vulnerabilities, leaked-secret remediation) in SECURITY.md
- [X] T035 [P] [US3] Write the pull-request template mirroring the constitution gate (tests at each layer, threat model considered, mutation threshold held, surviving-mutant justifications, bypass log empty, Constitution Check passed) in .github/PULL_REQUEST_TEMPLATE.md
- [X] T036 [P] [US3] Add the maintainer as owner of all paths (`* @raphaelpanta`) in .github/CODEOWNERS
- [X] T037 [P] [US3] Create the bug report issue template in .github/ISSUE_TEMPLATE/bug_report.md
- [X] T038 [P] [US3] Create the feature request issue template in .github/ISSUE_TEMPLATE/feature_request.md
- [X] T039 [P] [US3] Create the issue-chooser configuration (blank issues allowed, contact link to the private vulnerability reporting form) in .github/ISSUE_TEMPLATE/config.yml
- [X] T040 [US3] Add private vulnerability reporting (`PUT /repos/{owner}/{repo}/private-vulnerability-reporting`), squash-only merge settings (`allow_squash_merge` true, merge commits and rebase off, `delete_branch_on_merge`, PR title and body for squash), and best-effort Actions hardening (fork-PR approval `all_external_contributors`, default workflow token read-only, no PR approval by Actions, warn instead of fail when unavailable) in scripts/bootstrap-repo.sh
- [X] T041 [US3] Add branch protection on `main` via `PUT /repos/{owner}/{repo}/branches/main/protection` (1 approval, dismiss stale reviews, conversation resolution, linear history, no force push, no deletion, enforce admins unless `--admin-bypass` which is logged, `required_status_checks` only when `--require-check` is given), applied after the first push, in scripts/bootstrap-repo.sh
- [X] T042 [US3] Implement the collaboration checks (licence API reports `MIT`, protection fields match policy, squash-only flags, private vulnerability reporting enabled, community profile `health_percentage` 100) in scripts/verify-repo.sh

**Checkpoint**: All three stories pass their offline tests; the repository content is complete and ready to publish

---

## Phase 6: Polish & Cross-Cutting Concerns (live execution and validation)

**Purpose**: Whole-suite validation, then the maintainer-confirmed publication and its live verification

- [X] T043 Run `scripts/tests/run-all.sh` until green and run `gitleaks dir --config .gitleaks.toml` over the working tree to confirm zero findings, fixing any failure in the relevant script or document
- [X] T044 Run the real read-only rehearsal `scripts/bootstrap-repo.sh --dry-run` against the live `gh` session, review owner, name, description, topics and visibility with the maintainer, and confirm no `.git` directory was created
- [X] T045 **OUTWARD-FACING AND IRREVERSIBLE: REQUIRES EXPLICIT MAINTAINER CONFIRMATION BEFORE EXECUTION.** Run `scripts/bootstrap-repo.sh` (add `--require-check verify` only once feature 002 provides that workflow) to create the public repository `raphaelpanta/scalable-e-commerce-platform-kotlin` and push the initial history; do not run until the maintainer has approved the T044 output in chat, and never on the agent's own initiative
- [X] T046 Run `scripts/verify-repo.sh` against the live repository and confirm `VERIFIED` (anonymous visibility, clone tree equals local, no ignored paths, licence MIT, scanning, protection, community profile 100)
- [X] T047 Perform the seeded-secret push test from quickstart.md section 5 on a scratch branch using `.githooks/pre-push`, confirm it is blocked and clean up the scratch branch (SC-003)
- [X] T048 Perform the direct-push-to-`main` rejection test from quickstart.md section 6 and confirm GH006, then clean up the scratch branch (SC-003; skip if `--admin-bypass` was used)
- [X] T049 Run the signed-out visitor check and the second-machine anonymous clone from quickstart.md section 7, record the time to find README, licence, CONTRIBUTING.md and SECURITY.md (SC-001) and total publication time (SC-006) against specs/003-github-public-repo/quickstart.md
- [X] T050 After feature 002 delivers the `verify` workflow, re-run `scripts/bootstrap-repo.sh --require-check verify` (idempotent) and confirm `scripts/verify-repo.sh` reports the required status check; the runner safeguards of specs/004-ecommerce-platform-mvp/research.md section 4 (ephemeral runner, SHA-pinned actions) are tracked there

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies; can start immediately.
- **Foundational (Phase 2)**: Depends on Setup (T004 helpers, T003 stub) and BLOCKS all user stories.
- **User Stories (Phase 3 to 5)**: All depend on Foundational. US1 and US2 are both P1 and both edit scripts/bootstrap-repo.sh, so their script tasks (T011-T013, T015, T024, T025) run sequentially; their tests, config files and documents are parallel. US3 (P2) follows.
- **Polish (Phase 6)**: Depends on all three stories. T045 is the only outward-facing task and needs every content task in US1 to US3 complete, because the initial history must already contain the ignore rules, the clean constitution, the hook and the community files (FR-001, FR-002, FR-003). For that reason publication is executed once at the end rather than inside US1; US1 is validated offline by T008-T010 and live by T045-T046.

### User Story Dependencies

- **User Story 1 (P1)**: Starts after Foundational; no dependency on other stories for its offline tests.
- **User Story 2 (P1)**: Starts after Foundational; independent test files and config; its bootstrap safeguards (T024, T025) extend the same script as US1, so they follow T011-T015.
- **User Story 3 (P2)**: Starts after Foundational; community documents are fully independent; its bootstrap additions (T040, T041) follow T025.

### Within Each User Story

- Tests are written first and must fail before implementation.
- Config and documents before the script steps that reference them.
- Script steps in file order of the bootstrap pipeline, then the matching verify-repo.sh checks.
- Within Phase 6, strictly in order: T043, T044, then T045 (maintainer confirmation), then T046 to T049; T050 waits for feature 002.

### Parallel Opportunities

- T001-T004 (Setup) in parallel; T007 in parallel with T005-T006.
- All test tasks within a story marked [P] (different files) in parallel.
- T020-T023 (ignore rules, gitleaks config, hook, constitution edit) in parallel.
- T030-T039 (all community documents) in parallel.
- Different people can take US2 files and US3 documents at the same time; only the shared script files serialise.

---

## Parallel Example: User Story 3

```bash
# Launch all US3 tests together (different files):
Task: "Write the community-files test in scripts/tests/test_community_files.sh"
Task: "Extend the idempotency test in scripts/tests/test_bootstrap_idempotency.sh"
Task: "Extend the verify tests in scripts/tests/test_verify_repo.sh"

# Then launch all community documents together:
Task: "Create the MIT licence text in LICENSE"
Task: "Write the overview in README.md"
Task: "Write the contribution guide in CONTRIBUTING.md"
Task: "Add Contributor Covenant 2.1 in CODE_OF_CONDUCT.md"
Task: "Write the security policy in SECURITY.md"
Task: "Write the pull-request template in .github/PULL_REQUEST_TEMPLATE.md"
Task: "Add the owner entry in .github/CODEOWNERS"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (blocks all stories).
3. Complete Phase 3: User Story 1 and confirm T008-T010 pass offline.
4. STOP and VALIDATE: do not publish yet. A public push without User Story 2 safeguards is unsafe, so US2 is treated as part of the minimum publishable increment.

### Incremental Delivery

1. Setup + Foundational gives the harness and script skeletons.
2. Add US1, then US2: the publishable core (publication logic plus secret and ignore safeguards), all tested offline.
3. Add US3: community files, merge policy and branch protection.
4. Phase 6: validate, get the maintainer's confirmation, publish once (T045), verify live, run the two live gate tests.
5. When feature 002 lands, run T050 to bind the `verify` check to branch protection.

### Parallel Team Strategy

1. One person completes Setup and Foundational.
2. Then: A takes US2 files (T020-T023) and tests; B takes US3 documents (T030-T039) and tests; C takes the sequential bootstrap and verify script tasks in pipeline order.
3. Everyone reconvenes for Phase 6; T045 is performed only by the maintainer or on the maintainer's explicit go-ahead.

---

## Notes

- [P] tasks = different files, no dependencies.
- [Story] label maps a task to its user story; Setup, Foundational and Polish tasks are unlabelled.
- The only outward-facing, irreversible task is T045. Everything before it is local; every test uses a stub `gh` and temporary repositories, never GitHub.
- Test secrets are generated at runtime (for example `ghp_` plus 36 random characters); never commit a secret-shaped literal, because the hook and push protection would (correctly) block it.
- Spec gaps decided in planning: (1) the spec has no threat-model section, so it is recorded in plan.md; (2) GitHub's community-profile checklist (SC-005) also counts issue templates, so T037-T039 add them; (3) with one maintainer, "1 approval" plus `enforce_admins` blocks self-merge, so default is enforced admins with an explicit logged `--admin-bypass` opt-out; (4) Actions fork-PR approval and read-only token are set as best-effort repository hardening per specs/004-ecommerce-platform-mvp/research.md section 4, although not an explicit FR; (5) required status checks cannot be set until feature 002's `verify` workflow exists, so T050 re-runs the script with `--require-check verify`.
- Verify tests fail before implementing; commit after each task or logical group, but never push to the public remote before T045 is confirmed.
