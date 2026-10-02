# Phase 0 Research: Publish Repository Publicly on GitHub

**Feature**: 003-github-public-repo | **Date**: 2026-10-02

All decisions below were settled with the maintainer or follow from the spec and the constitution; no
open clarification items remain. Nothing here has been executed.

## 1. Secret Scanning: gitleaks vs trufflehog vs push protection only

- **Decision**: Two layers. (a) GitHub secret scanning plus push protection, enabled through
  `PATCH /repos/{owner}/{repo}` with `security_and_analysis.secret_scanning` and
  `secret_scanning_push_protection` set to `enabled`. (b) A local `gitleaks` pre-push hook:
  `.githooks/pre-push` reads the pushed ref ranges from stdin and runs `gitleaks git --redact
  --log-opts=<range>` with `--config .gitleaks.toml`; for a new branch or the initial push the range is
  the full history. The hook is activated per clone with `git config core.hooksPath .githooks`, which the
  bootstrap script sets and `CONTRIBUTING.md` documents. The hook fails closed: if `gitleaks` is not on
  `PATH` it refuses the push with install instructions (`brew install gitleaks`). `.gitleaks.toml` extends
  the default ruleset and allowlists only `scripts/tests/` fixtures that are assembled at runtime and
  therefore contain no literal secrets.
- **Rationale**: The hook reports file and line before data leaves the machine (User Story 2, scenario 1)
  and covers custom formats; push protection is the server-side backstop that works for every contributor
  and clone and is free for public repositories. Either alone has a gap (see Complexity Tracking in
  plan.md). gitleaks is a single static binary, installs with Homebrew, runs in seconds and supports
  git-history scanning with a TOML config.
- **Alternatives considered**: trufflehog (stronger live-credential verification, but heavier, network
  calls during scanning and noisier for a first version; can be added to CI later); push protection only
  (no local feedback, no test before the repository exists, pattern coverage limited to supported
  providers); pre-commit framework (adds a Python dependency and still needs per-clone installation).

## 2. Branch Protection: classic API vs rulesets

- **Decision**: Classic branch protection through `PUT /repos/{owner}/{repo}/branches/main/protection`
  with a JSON body passed to `gh api --input -`: `required_pull_request_reviews`
  (`required_approving_review_count: 1`, `dismiss_stale_reviews: true`), `required_conversation_resolution:
  true`, `required_linear_history: true`, `allow_force_pushes: false`, `allow_deletions: false`,
  `enforce_admins: true`, `restrictions: null`, and `required_status_checks` set to `null` until feature
  002's `verify` workflow exists, after which `bootstrap-repo.sh --require-check verify` sets
  `{strict: true, contexts: ["verify"]}`. Protection is applied after the initial push, because the first
  push to an empty repository must create `main`.
- **Rationale**: The endpoint is a single idempotent `PUT` (replaces the whole protection, so re-running
  converges), it maps one-to-one to FR-010, and `gh api` can read it back for verification
  (`GET .../protection`). It works for a personal public repository without extra plans.
- **Alternatives considered**: repository rulesets (more expressive, layered and able to target multiple
  branches, but a richer schema, `gh` has no first-class helper, and idempotent update requires looking up
  the ruleset id; revisit when moving to an organisation, which is out of scope); no protection until CI
  exists (violates FR-010 and leaves `main` open from day one).
- **Solo-maintainer note**: with one account, `enforce_admins` plus one required approval means the
  maintainer cannot merge without a second approver. This is deliberate for SC-003; the script offers an
  explicit `--admin-bypass` flag (sets `enforce_admins: false`, logged in the output) for a solo period.

## 3. Merge Method: squash vs rebase

- **Decision**: Squash merge only. `PATCH /repos/{owner}/{repo}` with `allow_squash_merge: true`,
  `allow_merge_commit: false`, `allow_rebase_merge: false`, `delete_branch_on_merge: true`,
  `squash_merge_commit_title: PR_TITLE`, `squash_merge_commit_message: PR_BODY`. Together with
  `required_linear_history` this gives one commit per pull request on `main`.
- **Rationale**: One reviewed pull request equals one commit, which matches the pull-request gate, keeps
  history readable, makes reverts atomic and tolerates messy intermediate commits on contributor branches.
  Linear history (FR-011) is guaranteed because merge commits are disabled.
- **Alternatives considered**: rebase merge (also linear, but replays every intermediate commit, so
  history depends on contributor commit hygiene and each commit would need to pass the gates); merge
  commits (non-linear, rejected by FR-011).

## 4. Community File Templates

- **Decision**: Hand-written first versions in the locations GitHub recognises: `README.md`, `LICENSE`,
  `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md` at the root; `.github/PULL_REQUEST_TEMPLATE.md`,
  `.github/CODEOWNERS` and `.github/ISSUE_TEMPLATE/` (bug report, feature request, `config.yml`) under
  `.github/`. `LICENSE` is the standard MIT text with `Copyright (c) 2026 <maintainer>` so GitHub's
  licensee detection reports `MIT`. `CODE_OF_CONDUCT.md` is Contributor Covenant 2.1 with the maintainer's
  contact method filled in (the private vulnerability reporting address is not reused for conduct
  reports). The pull-request template mirrors the constitution's pull-request gate: tests at each layer,
  threat model considered, mutation threshold held, surviving-mutant justifications, bypass log empty. The
  README links to the constitution and to `specs/` instead of duplicating them.
- **Rationale**: Matching GitHub's recognised paths makes the community-profile checklist and licence badge
  work (SC-005). The community profile also counts issue templates, so they are included even though the
  spec lists only the pull-request template (gap decision recorded in tasks.md Notes). Short documents
  satisfy the spec's "concise first versions" assumption.
- **Alternatives considered**: an organisation-level `.github` repository for shared defaults (out of
  scope, there is no organisation); generating files with a GitHub template repository (adds a dependency
  and is not reproducible offline).

## 5. Private Vulnerability Reporting

- **Decision**: Enable it with `PUT /repos/{owner}/{repo}/private-vulnerability-reporting` and verify with
  `GET` of the same path (`{"enabled": true}`). `SECURITY.md` points to the repository's Security tab
  ("Report a vulnerability"), states a first response within 5 business days, lists supported versions
  (latest `main` only for now) and asks reporters not to open public issues. `ISSUE_TEMPLATE/config.yml`
  adds a contact link to the same advisory form.
- **Rationale**: It is the hosting service's built-in private channel (spec assumption), needs no mailbox
  or key management, and keeps reports and fixes in a private advisory.
- **Alternatives considered**: a security mailbox (exposes a personal address, needs PGP for confidential
  detail); no channel (violates FR-008).

## 6. Repository Topics

- **Decision**: `PUT /repos/{owner}/{repo}/topics` with `names`: `ecommerce`, `microservices`, `kotlin`,
  `spring-boot`, `kafka`, `hexagonal-architecture`, `ddd`. Description (one line): "Scalable e-commerce
  platform in Kotlin: microservices, hexagonal architecture and DDD on Spring Boot and Kafka."
- **Rationale**: The call replaces the full list, so repeated runs converge. The topics cover the domain
  and the stack named in the constitution (FR-004). All are lower-case, under GitHub's 50-character limit,
  and fewer than the 20-topic cap.
- **Alternatives considered**: `gh repo edit --add-topic` (additive, so it cannot remove drift; the API
  `PUT` is declarative).

## 7. Idempotent Bootstrap Script Design

- **Decision**: `scripts/bootstrap-repo.sh` is a sequence of small steps, each of the form
  "read current state, act only if it differs". Order: (1) preflight, read-only: `gh auth status`, token
  scopes, owner resolved from `gh api user`, repository name availability, `git` identity, `gitleaks`
  presence; any failure stops before any local change (spec edge cases). (2) ensure `gitleaks` (Homebrew
  install, skipped in dry-run). (3) remove the constitution's Sync Impact Report comment if present
  (FR-003). (4) `git init -b main` if there is no repository. (5) set `core.hooksPath`. (6) stage
  everything, then unstage any path that matches ignore rules (`git ls-files -ci --exclude-standard` then
  `git rm --cached`). (7) scan with `gitleaks dir`/`git`; abort on findings. (8) create the single initial
  commit only if `HEAD` does not exist. (9) `gh repo create <owner>/<name> --public --description ...
  --source . --remote origin` only if the repository does not exist; if it exists and is not the
  expected remote, stop. (10) `git push -u origin main` (a no-op when up to date, so a retry after a
  network failure never duplicates commits). (11) repository settings, security and analysis, topics,
  private vulnerability reporting, merge methods and Actions hardening. (12) branch protection.
  (13) run `scripts/verify-repo.sh`. Flags: `--dry-run` (every mutating call goes through one
  `run`/`gh_write` wrapper that prints and skips it; reads still execute), `--yes` (skip the interactive
  confirmation, still required to be explicit), `--owner`, `--name`, `--require-check <context>`
  (repeatable), `--admin-bypass`, `--verbose`. The script refuses to run mutating steps without either
  `--yes` or an interactive "type the repository name to confirm" prompt, because creation and the first
  push are outward-facing and irreversible in practice. Output is quiet: one line per step and a final
  summary.
- **Rationale**: Per-step state checks make partial failures (service unreachable mid-run) recoverable by
  simply re-running, which satisfies the edge cases and FR-013. A single mutation wrapper makes the
  dry-run trustworthy and unit-testable with a stub `gh`.
- **Alternatives considered**: Terraform GitHub provider (powerful drift detection, but heavy state and a
  new toolchain for one repository, and cannot do the local git steps); a single `set -e` linear script
  (not safely re-runnable); a GitHub template repository (cannot enforce secret scanning or protection by
  itself).

## 8. Verification Design

- **Decision**: `scripts/verify-repo.sh [--repo owner/name]` checks, in order: visibility public using an
  unauthenticated request (`curl -fsS https://api.github.com/repos/...` with no token, and `git ls-remote`
  with `GIT_TERMINAL_PROMPT=0` and no credential helper); default branch is `main` and equals the local
  branch; an anonymous shallow-less clone into a temporary directory whose `git ls-tree -r HEAD` tree
  hash equals the local `HEAD` tree; no tracked path in the clone matches the ignore rules
  (`git ls-files -ci --exclude-standard`) and none of `.claude/.cache/`, `.claude/settings.local.json`
  and the Sync Impact Report text appear; licence API reports `MIT`; topics and description present;
  secret scanning and push protection `enabled`; private vulnerability reporting enabled; branch
  protection fields match the policy; squash-only merge flags; community profile `health_percentage`
  of 100. `REPO_ANON_URL` and `GH_BIN` environment overrides let tests point the script at a local bare
  repository and the stub `gh`.
- **Rationale**: Anonymous access is the only honest proof of publicness (SC-001, SC-002); comparing tree
  hashes proves "the clone matches the local working copy" (User Story 1, scenario 3) without caring about
  commit metadata.
- **Alternatives considered**: checking only `gh repo view --json visibility` (uses the maintainer's
  credentials and cannot detect that signed-out readers are denied).

## 9. Self-Hosted Runner Implications on a Public Repository

- **Decision**: This feature does not register a runner or create workflows (feature 002 delivers the
  `verify` workflow; specs/004 research section 4 decided on a containerised self-hosted runner). It
  prepares the repository-level safeguards that section 4 lists, as best-effort API calls in the
  bootstrap script: (a) `PUT /repos/{owner}/{repo}/actions/permissions/fork-pr-contributor-approval` with
  `approval_policy: all_external_contributors` ("require approval for all outside collaborators"); (b)
  `PUT /repos/{owner}/{repo}/actions/permissions/workflow` with `default_workflow_permissions: read` and
  `can_approve_pull_request_reviews: false`; (c) documentation in `CONTRIBUTING.md` and the README that
  workflows must trigger only on `push` to protected branches and on `pull_request` from collaborators,
  never `pull_request_target` with untrusted checkout, and that third-party actions are pinned by commit
  SHA. Ephemeral (just-in-time) runner registration and registry-secret scoping remain tasks of 002/004.
  If a call is unavailable on the account, the script prints a warning with the manual setting path
  rather than failing, and the quickstart records it as a manual step. The `required_status_checks`
  hook point (`--require-check verify`) is what later binds the runner-backed workflow to branch
  protection.
- **Rationale**: GitHub's own guidance is that self-hosted runners should almost never be used for public
  repositories because any pull request can execute code on them (004 research section 4). Settings that
  make fork pull requests wait for approval and keep the default token read-only cost nothing, belong to
  repository creation, and must exist before the first workflow can run.
- **Alternatives considered**: deferring all Actions hardening to feature 002 (leaves a window in which a
  workflow added later runs under permissive defaults); falling back to GitHub-hosted runners for pull
  requests (004's documented fallback if the mitigations cannot be met; not needed to decide here).

## 10. Initial-Publication Safety Details

- **Decision**: Ignore rules (`.gitignore`) cover build outputs (`build/`, `.gradle/`, `out/`, `bin/`,
  `node_modules/`, `dist/`), dependency caches, `.claude/.cache/`, `.claude/settings.local.json`,
  `.tokensave/`, `.rtk/`, IDE directories (`.idea/`, `.vscode/`, `*.iml`), `.DS_Store`, `*.log`, `*.tmp`,
  and secret-shaped files (`.env`, `.env.*` except `.env.example`, `*.pem`, `*.key`). `.specify/.gitignore`
  already ignores `feature.json`, so the machine-local feature pointer is not published either. The
  constitution's leading HTML Sync Impact Report comment is removed with a guarded edit (delete from
  `<!--` through `-->` only if the block contains "Sync Impact Report" and precedes the first heading) and
  the removal is covered by a test; the file's version line stays `1.1.0`.
- **Rationale**: A public repository is permanent in practice; the history must be clean from the first
  commit, and the constitution itself requires removing the comment before committing.
- **Alternatives considered**: rewriting history after the fact with `git filter-repo` (necessary only as
  the documented remediation for a later leak, described in `SECURITY.md` and `CONTRIBUTING.md`, together
  with credential rotation).
