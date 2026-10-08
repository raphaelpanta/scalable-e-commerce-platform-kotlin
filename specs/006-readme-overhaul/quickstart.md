# Quickstart: Validating the README Overhaul

## Prerequisites

The repository's usual tooling: Bash, `jq`, `shellcheck`, JDK 25 (for the Gradle gate). No network access needed for
the tests.

## 1. Automated checks

```bash
bash scripts/tests/test_community_files.sh
```

Expected: every case passes, including the new link, section-order and badge-drift cases and the negative case
that proves each check catches a broken copy.

```bash
scripts/lint.sh
```

Expected: no shellcheck findings.

```bash
./gradlew -q verify
```

Expected: exit 0, no output (runs the script tests through `scriptsTest`).

## 2. Drift check by hand

Change the Kotlin version badge, and rename one workflow in a badge URL (for example `cart.yml` → `basket.yml`), in
`README.md` and re-run the community-files test: it fails with `badge Kotlin: README <x>, source <y>` and
`workflow behind the basket.yml badge`. Revert (done once during implementation on 2026-10-08).

## 3. Rendering review on GitHub

Push the branch and open the README on github.com (light and dark theme, and a phone-width window):

- header shows licence, one build badge and the version badges; all images load;
- table of contents links jump to each section;
- the quick start begins within the first screenful after the pitch;
- the overview diagram renders; the purchase-flow diagram renders after expanding its section;
- alerts render as coloured callouts; collapsed sections open;
- services table shows one build badge per service.

## 4. Newcomer walkthrough (SC-001, SC-005)

Ask someone unfamiliar with the project to follow only the README on a prepared machine: they reach
`http://localhost:8080` with at most three commands, and after two minutes on the page can name the services and say
that they talk over HTTP through the gateway and over Kafka events.
