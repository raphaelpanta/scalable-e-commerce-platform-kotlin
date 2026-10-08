# Data Model: README Overhaul

The "data" is the structure of `README.md`. The test in `scripts/tests/test_community_files.sh` enforces the rules
marked **(tested)**; the exact values are in [contracts/readme-structure.md](contracts/readme-structure.md).

## Section

| Field | Rule |
|---|---|
| heading | `## <Title>`, unique; slug is the TOC anchor |
| position | fixed order from FR-001 **(tested)** |
| toc entry | every `##` heading has a `- [Title](#slug)` entry in the table of contents (FR-002) |
| collapsed | optional; `<details>` for manual start, purchase flow, repository publishing (FR-010) |

## Badge

| Field | Rule |
|---|---|
| label | e.g. `JDK`, `Kotlin`, `Spring Boot`, `Node`, `React`, `TypeScript`, `license` |
| value | for version badges, equals the source value **(tested)** |
| source | file and key from research section 2 |
| placement | header (licence, one `verify` build badge, versions) or services table (one workflow badge per row) |
| link | relative link (`LICENSE`) **(tested)** or this repository's Actions page; a workflow badge's file exists in `.github/workflows/` **(tested)** |

## Diagram

| Field | Rule |
|---|---|
| kind | `overview` (`flowchart`) or `purchase-flow` (`sequenceDiagram`) |
| visibility | overview always visible; purchase-flow inside `<details>` |
| count | exactly two Mermaid blocks (FR-007) |
| content | overview: storefront, gateway, the six services, Kafka, per-service databases; purchase-flow: cart → order → catalog reserve → payment → events → notification |

## Documentation map entry

| Field | Rule |
|---|---|
| title | document name |
| link | relative path that resolves **(tested)** |
| when to read | one line |

Entries: `docs/build.md`, `docs/dev-environment.md`, `docs/running-locally.md`, `docs/architecture.md`,
`docs/ci-cd.md`, `docs/storefront.md`, `docs/gateway.md`, `contracts/README.md`, `CONTRIBUTING.md`, `SECURITY.md`.
