# Implementation Plan: Publish Repository Publicly on GitHub

**Branch**: `003-github-public-repo` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-github-public-repo/spec.md`

**Note**: This plan covers planning only. Nothing in it has been executed; no git repository, GitHub repository or setting exists yet.

## Summary

Turn the unversioned project directory into a public GitHub repository, `raphaelpanta/scalable-e-commerce-platform-kotlin`,
with `main` as default branch, MIT licence, community-health files, secret protection at two independent layers
and a protected `main`. The whole procedure is delivered as two idempotent Bash scripts (`scripts/bootstrap-repo.sh`
with a `--dry-run` flag, and `scripts/verify-repo.sh`) built on `git` and the already-authenticated `gh` CLI,
so the same setup can be re-applied to future platform repositories (FR-013). Secrets are stopped locally by a
`gitleaks` pre-push hook (`.githooks/pre-push`, configured by `.gitleaks.toml`, activated with
`core.hooksPath`) and remotely by GitHub secret scanning with push protection. Branch protection, squash-only
merging, private vulnerability reporting, topics and Actions fork-PR hardening are applied through `gh api`.
Shell tests under `scripts/tests/` use a stubbed `gh` and temporary git repositories so that no test touches
GitHub. The single outward-facing act (create the repository, push) is isolated in one task that requires the
maintainer's explicit confirmation. See [research.md](research.md) for the decisions.

## Technical Context

**Language/Version**: Bash 3.2-compatible (macOS default) with POSIX utilities; scripts start with `set -euo pipefail`

**Primary Dependencies**: `git` (configured user), `gh` 2.x authenticated as `raphaelpanta` (scopes `repo`, `workflow`, `read:org`), `gitleaks` >= 8.19 (`gitleaks git` subcommand; installed via Homebrew if absent), `curl`; JSON is filtered with `gh --jq`, so `jq` is not required

**Storage**: N/A (state lives in the git repository and in GitHub settings; the script derives it on each run)

**Testing**: Plain-Bash test files under `scripts/tests/` run by `scripts/tests/run-all.sh` (bats is not installed and is not required); a stub `gh` and temporary git repositories isolate tests from GitHub; seeded secrets are assembled at runtime so no secret-shaped literal is committed

**Target Platform**: Maintainer workstation (macOS/Linux shell) and, for verification only, a second machine performing an anonymous clone

**Project Type**: Repository-operations tooling (CLI scripts plus community documents); no application code

**Performance Goals**: Whole publication from unversioned directory to verified public repository in under 30 minutes (SC-006); the pre-push scan of the initial history completes in seconds

**Constraints**: Idempotent and re-runnable after partial failure without duplicating commits; `--dry-run` performs only read-only calls; fail closed when `gitleaks` is missing; no secrets or tokens in the repository, the scripts' output or the logs; quiet output by default (failures and a final summary only)

**Scale/Scope**: One repository, about 20 tracked files of tooling and documentation plus the existing `specs/`, `.specify/` and `.claude/` content

No open clarification items remain; every unknown was resolved in [research.md](research.md).

## Threat Model (Constitution Principle III)

The spec does not contain a threat-model section; it is recorded here, as required by the constitution, until the spec is amended.

- **Assets**: credentials and tokens on the maintainer machine; personal data in local settings and agent caches; unpublished review scratch (the constitution Sync Impact Report comment); integrity of `main`; the maintainer's GitHub account and its `gh` token; the self-hosted runner host (feature 002/004) that later executes workflows from this repository.
- **Actors**: maintainer; outside contributors and fork authors; anonymous internet readers and scrapers; security researchers; a compromised collaborator account; automated secret-harvesting bots.
- **Abuse cases**: (1) a secret is committed and pushed, then harvested within minutes; (2) a local-only file (`.claude/settings.local.json`, `.claude/.cache/`) is published; (3) an unreviewed or force-pushed change lands on `main`; (4) a vulnerability is reported in public because no private channel exists; (5) a fork pull request runs untrusted code on the self-hosted runner; (6) the bootstrap script is run with a wrong owner or name and publishes to the wrong place; (7) a malicious or typo'd value is interpolated into a `gh api` call or shell command.
- **Trust boundaries**: local working copy to GitHub (push); anonymous reader to the public repository; fork contributor to repository workflows; maintainer shell to `gh` token scope.
- **Mitigations**: ignore rules plus a staged-ignored-path check before the first commit (abuse 2); gitleaks pre-push hook and GitHub push protection, either of which blocks a seeded secret (1); branch protection with enforced admins, linear history, required review (3); private vulnerability reporting and `SECURITY.md` (4); repository setting "require approval for all outside collaborators", read-only default workflow token and SHA-pinned actions, per 004 research section 4 (5); dry-run default review, owner/name echoed and confirmation required before the outward-facing step (6); all variables quoted, `gh api` fields passed with `-f`/`-F`/`--input`, never by string-built URLs from untrusted input (7).
- **OWASP Top 10 / API Security Top 10**: not applicable, the feature exposes no network service, endpoint, consumer or job; the relevant concerns (secret leakage, supply chain, access control on `main`) are covered above.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | Kotlin-Idiomatic Monorepo | N/A | No Kotlin, Gradle or build scripts are introduced; no Groovy DSL. Tooling is shell, outside the Gradle build. |
| II | Clean / Hexagonal Architecture with DDD | N/A | No service or domain code. |
| III | Security by Design (NON-NEGOTIABLE) | PASS | Threat model recorded above. Controls: ignore rules, secret-free history verified by gitleaks before the first push, local pre-push hook plus GitHub secret scanning and push protection, protected `main`, private vulnerability reporting, fork-PR hardening for the later self-hosted runner. No secrets in source, logs or fixtures (seeded test secrets are generated at runtime). OWASP lists not applicable (no network surface). Re-check after design: still PASS. |
| IV | Functional & Non-Blocking First | N/A | No application code. Scripts are small, idempotent steps with side effects isolated in a few functions. |
| V | Layered Test Contract (NON-NEGOTIABLE) | N/A | The Kotlin/TypeScript layers do not apply. Equivalent shell tests are mandatory here and exist per story: dry-run, idempotency, seeded-secret hook, ignore rules, verify checks, community files. Mutation testing does not apply to shell. |
| VI | Microservice Boundaries & Contracts | N/A | No service. |
| VII | TypeScript + React Frontend | N/A | No frontend. |
| VIII | Token-Efficient, Hook-Driven Harness Engineering | PASS | Scripts and tests are quiet by default (failures plus one summary line, `--verbose` to expand). The pre-push hook is a hook-driven gate consistent with the principle. `PULL_REQUEST_TEMPLATE.md` mirrors the pull-request gate and the "bypass log empty" item from feature 001. |

Governance: this feature depends on the constitution being published without its temporary Sync Impact Report comment (FR-003); that edit is a task, not an amendment, so no version bump is required.

## Project Structure

### Documentation (this feature)

```text
specs/003-github-public-repo/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md  # Spec quality checklist (existing)
└── tasks.md             # Phase 2 output (/speckit-tasks command)
```

data-model.md and contracts/ are not produced: the key entities are GitHub settings with no persisted schema, and the only interface is the scripts' command-line flags, documented in quickstart.md.

### Source Code (repository root)

```text
.gitignore                         # ignore rules (FR-002)
.gitleaks.toml                     # gitleaks configuration and allowlist (FR-009)
.githooks/
└── pre-push                       # local secret gate, activated via core.hooksPath (FR-009)
.github/
├── CODEOWNERS                     # maintainer owns everything (FR-008 support)
├── PULL_REQUEST_TEMPLATE.md       # constitution PR gate checklist (FR-008)
└── ISSUE_TEMPLATE/
    ├── bug_report.md              # community-health checklist item (SC-005)
    ├── feature_request.md
    └── config.yml                 # links to private vulnerability reporting
README.md                          # overview, architecture, build/verify, links (FR-007)
LICENSE                            # MIT, 2026, maintainer (FR-006)
CONTRIBUTING.md                    # how to contribute, hook activation, merge policy (FR-008)
CODE_OF_CONDUCT.md                 # Contributor Covenant 2.1 (FR-008)
SECURITY.md                        # private reporting channel, 5 business day response (FR-008)
scripts/
├── bootstrap-repo.sh              # idempotent publication procedure, --dry-run (FR-001, 004, 005, 009-011, 013)
├── verify-repo.sh                 # post-publication verification (FR-012)
├── lib/
│   └── common.sh                  # logging, dry-run wrapper, gh/git helpers shared by both scripts
└── tests/
    ├── run-all.sh                 # runs every test file, quiet, exits non-zero on failure
    ├── lib/
    │   └── assert.sh              # tiny assertion helpers
    ├── stubs/
    │   └── gh                     # stateful fake gh for offline tests
    ├── test_ignore_rules.sh
    ├── test_constitution_clean.sh
    ├── test_community_files.sh
    ├── test_pre_push_hook.sh
    ├── test_bootstrap_dry_run.sh
    ├── test_bootstrap_idempotency.sh
    └── test_verify_repo.sh
```

**Structure Decision**: tooling lives in `scripts/` with a shared `lib/common.sh`; community documents follow GitHub's recognised locations (root and `.github/`) so the community-profile checklist recognises them; tests sit beside the scripts and never contact GitHub (a stub `gh` on `PATH` and local bare repositories stand in for it). No `src/` tree exists because there is no application code.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Two secret-scanning layers: local gitleaks pre-push hook AND GitHub push protection | The hook gives the maintainer the file and line before anything leaves the machine and scans history on the initial push; push protection is the server-side backstop that still works when the hook is not installed, is bypassed with `--no-verify`, or a contributor clones fresh. Settled by the maintainer. | Push protection alone only covers provider-known token patterns and cannot be tested before the repository exists or on private custom formats; the hook alone is client-side, per-clone (`core.hooksPath` is not versioned config) and trivially bypassed. |
| Custom bootstrap and verify scripts instead of clicking in the GitHub UI | FR-013 requires reproducibility for future platform repositories; SC-006 requires a 30-minute documented procedure; the scripts are testable offline. | UI steps are neither repeatable nor testable and cannot be dry-run. |
| `enforce_admins` on branch protection (the maintainer cannot self-merge without a second approver) | SC-003 demands 100% of direct pushes to `main` rejected, including the maintainer's. | Leaving admins exempt would let the maintainer, the only account today, push directly and defeat the quality gates; `--admin-bypass` is provided as an explicit, logged opt-out for a solo-maintainer period. |

## FR to Artifact Traceability

| Requirement | Artifact(s) | Verified by |
|-------------|-------------|-------------|
| FR-001 single initial history, complete content | `scripts/bootstrap-repo.sh` (init, stage, single commit) | `scripts/tests/test_bootstrap_idempotency.sh`, `scripts/verify-repo.sh` (tree compare) |
| FR-002 ignore rules before publication | `.gitignore`, bootstrap staged-ignored check | `scripts/tests/test_ignore_rules.sh` |
| FR-003 constitution comment removed | `.specify/memory/constitution.md` edit, bootstrap guard | `scripts/tests/test_constitution_clean.sh` |
| FR-004 public repo, name, description, topics | `scripts/bootstrap-repo.sh` (`gh repo create`, topics API) | `scripts/tests/test_bootstrap_dry_run.sh`, `scripts/verify-repo.sh` |
| FR-005 complete history pushed, remote tracked | `scripts/bootstrap-repo.sh` (`git push -u origin main`) | `scripts/verify-repo.sh` (anonymous clone compare) |
| FR-006 MIT licence | `LICENSE` | `scripts/tests/test_community_files.sh`, `scripts/verify-repo.sh` (licence API reports MIT) |
| FR-007 overview | `README.md` | `scripts/tests/test_community_files.sh` |
| FR-008 contribution guide, code of conduct, security policy, PR template | `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/CODEOWNERS`, `.github/ISSUE_TEMPLATE/*`, private vulnerability reporting call in bootstrap | `scripts/tests/test_community_files.sh`, `scripts/verify-repo.sh` |
| FR-009 secret scanning, push protection, local pre-push check | `.gitleaks.toml`, `.githooks/pre-push`, `core.hooksPath`, `security_and_analysis` call in bootstrap | `scripts/tests/test_pre_push_hook.sh`, `scripts/verify-repo.sh`, quickstart seeded-push test |
| FR-010 protected `main` | branch-protection call in bootstrap (required checks added by `--require-check verify` once feature 002 delivers it) | `scripts/tests/test_bootstrap_idempotency.sh`, `scripts/verify-repo.sh`, quickstart direct-push test |
| FR-011 linear history | squash-only merge settings plus `required_linear_history` | `scripts/verify-repo.sh` |
| FR-012 self-verification | `scripts/verify-repo.sh`, invoked at the end of bootstrap | `scripts/tests/test_verify_repo.sh` |
| FR-013 reproducible procedure | `scripts/bootstrap-repo.sh`, `scripts/lib/common.sh`, `quickstart.md`, `CONTRIBUTING.md` | quickstart run (SC-006) |
