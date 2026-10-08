# Implementation Plan: README Overhaul

**Branch**: `006-readme-overhaul` (spec directory; work happens on a branch cut from `main` at implementation time) | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-readme-overhaul/spec.md`

## Summary

Rewrite the root `README.md` so the quick start comes first, the page is scannable (table of contents, tables,
GitHub callouts, collapsed optional sections), it carries badges (licence, one `verify` build badge, versions, one
build badge per service) and two Mermaid diagrams (architecture overview, collapsed purchase-flow sequence), and every
stale fact is corrected. Extend the existing offline README test (`scripts/tests/test_community_files.sh`) with
checks for relative links, section order and badge versions, written as functions over a file path so the same
checks are proven to fail against a deliberately broken copy, plus an inline assertion that every workflow badge names
an existing `.github/workflows/` file (spec edge case "CI badge for a removed workflow"). The test already runs inside
`./gradlew -q verify` through `scriptsTest`.

**Revision 2026-10-08 (re-plan after implementation)**: design files aligned with what was built: the workflow-badge
assertion, the TypeScript source fallback, the hard 250-line limit and source-line measurement for SC-002. No change
to scope or to the spec.

## Technical Context

**Language/Version**: GitHub-flavored Markdown (GFM with Mermaid, alerts, `<details>`); Bash 3.2+ for the test
(macOS default shell compatibility, as in the existing script tests)

**Primary Dependencies**: shields.io static badges and GitHub Actions workflow badges (rendered by the browser, not
fetched by tests); existing `scripts/tests/lib/assert.sh`

**Storage**: N/A

**Testing**: `scripts/tests/run-all.sh` (offline), wired into `./gradlew -q verify` via `scriptsTest`
(`build-logic/src/main/kotlin/repository-root.gradle.kts:100`); `scripts/lint.sh` (shellcheck) for the edited test

**Target Platform**: github.com repository page (light and dark themes, desktop and mobile widths)

**Project Type**: documentation plus repository script test

**Performance Goals**: the extended test adds under 1 s to `run-all.sh`

**Constraints**: README ≤ 250 source lines; `## Quick start` within the first 40 source lines (SC-002 is measured on
the source, which is what a test can check; the rendered position is reviewed in quickstart section 3); no binary images;
no network access in tests; keep every existing assertion in `test_community_files.sh:24-33`

**Scale/Scope**: one Markdown file rewritten, one test file extended; no Gradle, Kotlin or frontend change

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Applies? | Assessment |
|---|---|---|
| I. Kotlin-idiomatic monorepo | No code | Pass: versions still live only in `gradle/libs.versions.toml`; README badges are checked against it (FR-015), not a second source of truth. |
| II. Hexagonal / DDD | No | Pass: N/A. |
| III. Security by design | Low | Pass: no secrets; existing "no secret-shaped literal" assertion kept; badges link only to shields.io and this repository's Actions. Threat model: none needed beyond that (no runtime surface). |
| IV. Functional and non-blocking | No | Pass: N/A. |
| V. Layered test contract | Partly | Pass: the applicable layer is the repository script test; checks are written test-first and proven against a broken fixture copy. Pact, Cucumber, property tests do not apply to a static document. |
| VI. Service boundaries | No | Pass: README links to `contracts/` and `docs/architecture.md`, does not restate contracts. |
| VII. TypeScript + React frontend | No | Pass: N/A. |
| VIII. Harness engineering | Yes | Pass: test stays quiet (failures only), runs in `verify`; no mutation tooling applies to Bash/Markdown (no `domain`/`application` code changed). |

No violations. Post-design re-check (after Phase 1): unchanged, all pass.

## Project Structure

### Documentation (this feature)

```text
specs/006-readme-overhaul/
├── plan.md              # This file
├── research.md          # Phase 0: GFM features, badge sources, test approach
├── data-model.md        # Phase 1: sections, badges, diagrams, doc-map entries and their rules
├── quickstart.md        # Phase 1: how to validate the feature
├── contracts/
│   └── readme-structure.md   # Section order, required strings, badge and diagram contract the test enforces
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
README.md                              # rewritten
scripts/tests/test_community_files.sh  # existing README case kept; new cases for links, order, badges
```

**Structure Decision**: the checks stay in `test_community_files.sh` (it already owns README assertions) as small
functions taking a README path, so a second test case can run them against a mutated copy in `mk_tmp`. No new test
file, no new Gradle task.

## Complexity Tracking

No constitution violations to justify.
