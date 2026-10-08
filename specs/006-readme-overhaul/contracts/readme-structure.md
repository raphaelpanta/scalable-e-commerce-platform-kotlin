# Contract: README Structure

What `README.md` must contain, and what `scripts/tests/test_community_files.sh` checks. Changing this contract
means changing the test in the same pull request.

## Required `##` headings, in this order (FR-001)

1. `## Contents`
2. `## Overview`
3. `## Quick start`
4. `## Architecture`
5. `## Repository layout`
6. `## Build and test`
7. `## Documentation`
8. `## Contributing`
9. `## License`

The title (`# Scalable E-Commerce Platform (Kotlin)`), one-line pitch and header badges precede `## Contents`.
`## Quick start` contains, as `###` subsections: Prerequisites, Start, What you get, Status and stop.
`## Architecture` contains the overview diagram, the services table and the collapsed purchase-flow diagram.

## Kept from the existing test (FR-011)

`# ` title; the words architecture, build, verify; `.specify/memory/constitution.md`; `specs/`;
`scripts/bootstrap-repo.sh`; `license-MIT`; no secret-shaped literal.

## Version badges (FR-003, FR-015)

Badge form: `https://img.shields.io/badge/<Label>-<value>-<colour>` (spaces in labels written `%20`).

| Label in badge URL | Source | Read as |
|---|---|---|
| `JDK` | `.java-version` | whole file, trimmed |
| `Kotlin` | `gradle/libs.versions.toml` | `kotlin = "<v>"` |
| `Spring%20Boot` | `gradle/libs.versions.toml` | `spring-boot = "<v>"` |
| `Node` | `frontend/package.json` | `engines.node` first major number |
| `React` | `frontend/package.json` | `dependencies.react` |
| `TypeScript` | `frontend/package.json` | `devDependencies.typescript` or `dependencies.typescript` |

## Build badges

- Header: exactly one, `actions/workflows/verify.yml/badge.svg`.
- Services table: one per row, `actions/workflows/<service>.yml/badge.svg` for identity, catalog, cart, order,
  payment, notification, gateway, plus `storefront.yml` for the storefront row.
- Every `actions/workflows/<file>/badge.svg` in the page names an existing `.github/workflows/<file>` (checked inline
  in the README test case, reported as `workflow behind the <file> badge`).

## Length

At most 250 source lines; `## Quick start` within the first 40 source lines.

## Diagrams

Exactly two ` ```mermaid ` blocks: the first a `flowchart`, outside `<details>`; the second a `sequenceDiagram`, inside
`<details>`.

## Links

Every Markdown link target that is not `http(s)://`, `mailto:` or a pure `#anchor` resolves (after dropping any
`#fragment`) to an existing path relative to the repository root.

## Check functions (test interface)

Each takes a README path and prints one line per violation; no output means pass.

| Function | Violation line |
|---|---|
| `readme_broken_links FILE` | `broken link: <target>` |
| `readme_section_order FILE` | `section missing or out of order: <heading>` |
| `readme_badge_drift FILE` | `badge <Label>: README <x>, source <y>` |
