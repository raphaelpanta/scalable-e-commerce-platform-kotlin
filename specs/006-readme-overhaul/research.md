# Research: README Overhaul

## 1. Diagrams

- **Decision**: Mermaid fenced blocks (` ```mermaid `): a `flowchart LR` overview and a `sequenceDiagram` for the
  purchase flow inside `<details>`.
- **Rationale**: GitHub renders Mermaid natively and themes it for light and dark mode; text lives in the repository
  and diffs cleanly; `docs/architecture.md` already uses Mermaid, so the overview can be a simplified version of that
  diagram (gateway, six services, Kafka, per-service PostgreSQL, storefront).
- **Alternatives considered**: SVG/PNG files (hard-coded colours break in dark mode, binary diffs, rejected by spec
  assumption); PlantUML (not rendered natively by GitHub).

## 2. Badges

- **Decision**: shields.io static badges for versions and licence (`img.shields.io/badge/<label>-<value>-<colour>`),
  GitHub's native workflow badge (`github.com/raphaelpanta/scalable-e-commerce-platform-kotlin/actions/workflows/<file>/badge.svg`)
  for `verify` in the header and for each service workflow (`identity.yml`, `catalog.yml`, `cart.yml`, `order.yml`,
  `payment.yml`, `notification.yml`, `gateway.yml`, `storefront.yml`) in the services table.
- **Rationale**: native workflow badges need no third party and always reflect real state; static version badges
  keep the page readable offline, and FR-015 catches drift.
- **Alternatives considered**: shields.io dynamic badges reading TOML/JSON from the repo (would be self-updating but
  depend on the default branch layout and a third-party fetch; static plus a test is simpler and deterministic).
- **Version sources** (what the test compares against):
  - JDK: `.java-version` (`25`)
  - Kotlin: `gradle/libs.versions.toml` key `kotlin`
  - Spring Boot: `gradle/libs.versions.toml` key `spring-boot`
  - Node: `frontend/package.json` `engines.node` major (`24`)
  - React, TypeScript: `frontend/package.json` dependency values
- The licence badge keeps the existing `license-MIT` substring required by the current test.

## 3. Rich formatting

- **Decision**: GitHub alerts (`> [!NOTE]`, `> [!TIP]`, `> [!WARNING]`) for the 10 GiB engine memory need and the
  Podman `BUILDAH_FORMAT=docker` setting; `<details><summary>` for manual start, purchase flow and repository
  publishing; a table of contents as a plain linked list (GitHub's auto-outline is not visible on mobile).
- **Rationale**: all supported on github.com; alerts degrade to blockquotes elsewhere.
- **Alternatives considered**: emoji headings (noisy, break anchor slugs); HTML tables (harder to maintain).

## 4. Test approach for FR-015

- **Decision**: in `scripts/tests/test_community_files.sh`, add three functions taking a README path and printing
  offending items (empty output = pass):
  - `readme_broken_links FILE`: extracts `](target)` links, skips `http(s)://`, `mailto:` and pure `#anchor`, strips
    `#fragment`, resolves relative to the repository root, reports targets that do not exist.
  - `readme_section_order FILE`: reads `^## ` headings and checks the required headings from the contract appear in
    order (extra headings allowed between them).
  - `readme_badge_drift FILE`: for each version badge label in the contract, extracts the badge value and compares it
    to the value read from its source file.
  Then two test cases: one asserts all three print nothing for `README.md` and that every
  `actions/workflows/<file>/badge.svg` names an existing `.github/workflows/<file>` (added after `/speckit-analyze`
  finding U1, since the link check skips `https://` URLs); one copies the README into `mk_tmp`,
  applies one mutation per check (bad link, swapped sections, wrong Kotlin version) and asserts each check reports it.
- **Rationale**: reuses `assert.sh`; offline; the negative case satisfies SC-004 and guards against vacuous checks.
- **Alternatives considered**: a Markdown link checker (`lychee`, `markdown-link-check`) — new tool dependency, and
  external links are out of scope; a Gradle/Kotlin test — heavier than needed for a shell-owned file.
- Parsing stays POSIX `grep`/`sed`/`awk` (no `jq` on TOML; `package.json` values read with `jq`, which is already a
  repository prerequisite).

## 5. Stale facts to correct (FR-012)

| Current README | Correct |
|---|---|
| "Spring Boot 3" | Spring Boot 4 (catalog `4.1.1`) |
| `./gradlew check` as the gate | `./gradlew -q verify` (`docs/build.md:84`) |
| "Once it [the Gradle build] is present" | build is present; sentence removed |
| Quick start after architecture and build | Quick start immediately after overview |
