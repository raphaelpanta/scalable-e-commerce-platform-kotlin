#!/usr/bin/env bash
# Community-files test: recognised paths and required content (FR-006, FR-007, FR-008).
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"

cd "$PROJECT_ROOT" || exit 1
lower() { tr '[:upper:]' '[:lower:]' <"$1"; }

# README checks (specs/006-readme-overhaul/contracts/readme-structure.md): each takes a README path and prints one
# line per violation; no output means pass.
readme_prose() { awk '/^```/ { fence = !fence; next } !fence' "$1"; } # drops fenced code blocks

readme_broken_links() { # FILE: relative link targets that do not exist under the repository root
  local target path
  readme_prose "$1" | grep -oE '\]\([^) ]+\)' | sed -E 's/^\]\(//; s/\)$//' | while IFS= read -r target; do
    case "$target" in http://* | https://* | mailto:* | '#'*) continue ;; esac
    path="${target%%#*}"
    [ -e "$PROJECT_ROOT/$path" ] || printf 'broken link: %s\n' "$target"
  done
}

README_SECTIONS="Contents|Overview|Quick start|Architecture|Repository layout|Build and test|Documentation|Contributing|License"

readme_section_order() { # FILE: first required ## heading that is missing or out of order
  local headings required rest
  headings="$(readme_prose "$1" | sed -nE 's/^## +(.*[^ ]) *$/\1/p')"
  rest="$headings"
  local IFS='|'
  for required in $README_SECTIONS; do
    if ! printf '%s\n' "$rest" | grep -qxF "$required"; then
      printf 'section missing or out of order: %s\n' "$required"
      return 0
    fi
    rest="$(printf '%s\n' "$rest" | sed -n "/^$required\$/,\$p" | sed 1d)"
  done
}

readme_missing_workflows() { # FILE: build badges whose workflow file is not in .github/workflows
  local wf
  grep -oE 'actions/workflows/[^/]+/badge\.svg' "$1" | sed -E 's|actions/workflows/||; s|/badge\.svg||' |
    while IFS= read -r wf; do
      [ -f "$PROJECT_ROOT/.github/workflows/$wf" ] || printf 'workflow behind the %s badge\n' "$wf"
    done
}

readme_badge_source() { # LABEL: the version the repository's sources declare for a badge label
  case "$1" in
    JDK) tr -d '[:space:]' <"$PROJECT_ROOT/.java-version" ;;
    Kotlin) sed -nE 's/^kotlin = "([^"]+)"$/\1/p' "$PROJECT_ROOT/gradle/libs.versions.toml" ;;
    Spring%20Boot) sed -nE 's/^spring-boot = "([^"]+)"$/\1/p' "$PROJECT_ROOT/gradle/libs.versions.toml" ;;
    Node) jq -r '.engines.node' "$PROJECT_ROOT/frontend/package.json" | grep -oE '[0-9]+' | head -n 1 ;;
    React) jq -r '.dependencies.react' "$PROJECT_ROOT/frontend/package.json" ;;
    TypeScript) jq -r '.devDependencies.typescript // .dependencies.typescript' "$PROJECT_ROOT/frontend/package.json" ;;
  esac
}

readme_badge_drift() { # FILE: version badges missing or differing from their sources
  local label shown expected
  for label in JDK Kotlin Spring%20Boot Node React TypeScript; do
    shown="$(grep -oE "img\.shields\.io/badge/$label-[^-)]+-" "$1" | head -n 1 | sed -E "s|.*/$label-||; s|-\$||")"
    expected="$(readme_badge_source "$label")"
    [ "$shown" = "$expected" ] || printf 'badge %s: README %s, source %s\n' "$label" "${shown:-missing}" "$expected"
  done
}

test_case "all community files exist at GitHub-recognised paths"
for f in README.md LICENSE CONTRIBUTING.md CODE_OF_CONDUCT.md SECURITY.md .github/PULL_REQUEST_TEMPLATE.md \
  .github/CODEOWNERS .github/ISSUE_TEMPLATE/bug_report.md .github/ISSUE_TEMPLATE/feature_request.md \
  .github/ISSUE_TEMPLATE/config.yml; do
  assert_file_exists "$f"
done

test_case "LICENSE is MIT, 2026, maintainer from git config"
maintainer="$(git config user.name || true)"
[ -n "$maintainer" ] || _fail "git config user.name is empty"
assert_contains "$(cat LICENSE)" "MIT License" "licence title"
assert_contains "$(cat LICENSE)" "Copyright (c) 2026 $maintainer" "copyright line"
assert_contains "$(cat LICENSE)" "Permission is hereby granted, free of charge" "MIT grant"
assert_contains "$(cat LICENSE)" "THE SOFTWARE IS PROVIDED \"AS IS\"" "MIT warranty disclaimer"

test_case "README has purpose, architecture, build/verify and links"
r="$(cat README.md)"
assert_contains "$r" "# " "title heading"
assert_contains "$(lower README.md)" "architecture" "architecture summary"
assert_contains "$(lower README.md)" "build" "build instructions"
assert_contains "$(lower README.md)" "verify" "verify instructions"
assert_contains "$r" ".specify/memory/constitution.md" "constitution link"
assert_contains "$r" "specs/" "specs link"
assert_contains "$r" "scripts/bootstrap-repo.sh" "reproduction instructions"
assert_contains "$r" "license-MIT" "licence badge"

test_case "README checks catch a broken copy"
d="$(mk_tmp)"
{
  printf '# Fixture\n\n[licence](LICENSE)\n![verify](https://github.com/o/r/actions/workflows/verify.yml/badge.svg)\n'
  for label in JDK Kotlin Spring%20Boot Node React TypeScript; do
    printf '![%s](https://img.shields.io/badge/%s-%s-blue)\n' "$label" "$label" "$(readme_badge_source "$label")"
  done
  (
    IFS='|'
    for s in $README_SECTIONS; do printf '\n## %s\n' "$s"; done
  )
} >"$d/ok.md"
assert_eq "" "$(readme_broken_links "$d/ok.md")" "valid fixture links"
assert_eq "" "$(readme_section_order "$d/ok.md")" "valid fixture order"
assert_eq "" "$(readme_badge_drift "$d/ok.md")" "valid fixture badges"
assert_eq "" "$(readme_missing_workflows "$d/ok.md")" "valid fixture workflows"
sed 's|(LICENSE)|(docs/does-not-exist.md)|' "$d/ok.md" >"$d/link.md"
assert_eq "broken link: docs/does-not-exist.md" "$(readme_broken_links "$d/link.md")" "broken link reported"
sed 's/^## Overview$/## TMP/; s/^## Quick start$/## Overview/; s/^## TMP$/## Quick start/' "$d/ok.md" >"$d/order.md"
assert_eq "section missing or out of order: Quick start" "$(readme_section_order "$d/order.md")" "swap reported"
sed -E 's|badge/Kotlin-[^-]+-|badge/Kotlin-0.0.1-|' "$d/ok.md" >"$d/badge.md"
assert_eq "badge Kotlin: README 0.0.1, source $(readme_badge_source Kotlin)" "$(readme_badge_drift "$d/badge.md")" \
  "badge drift reported"
sed 's|workflows/verify.yml/badge|workflows/does-not-exist.yml/badge|' "$d/ok.md" >"$d/workflow.md"
assert_eq "workflow behind the does-not-exist.yml badge" "$(readme_missing_workflows "$d/workflow.md")" \
  "missing workflow reported"

test_case "README links resolve, sections are in order and badges match their sources"
assert_eq "" "$(readme_broken_links README.md)" "README links"
assert_eq "" "$(readme_section_order README.md)" "README section order"
assert_eq "" "$(readme_badge_drift README.md)" "README version badges"
assert_eq "" "$(readme_missing_workflows README.md)" "README build badges"

test_case "SECURITY.md states 5 business days and private reporting"
assert_contains "$(lower SECURITY.md)" "5 business days" "response time"
assert_contains "$(lower SECURITY.md)" "private vulnerability reporting" "private channel"
assert_contains "$(lower SECURITY.md)" "supported versions" "supported versions"

test_case "CODE_OF_CONDUCT.md is Contributor Covenant 2.1"
assert_contains "$(cat CODE_OF_CONDUCT.md)" "Contributor Covenant" "name"
assert_contains "$(cat CODE_OF_CONDUCT.md)" "version 2.1" "version"
assert_contains "$(cat CODE_OF_CONDUCT.md)" "## Enforcement" "enforcement section"

test_case "PULL_REQUEST_TEMPLATE.md carries the five gate items"
n="$(grep -c '^- \[ \]' .github/PULL_REQUEST_TEMPLATE.md || true)"
[ "$n" -ge 5 ] || _fail "expected at least 5 checklist items, found $n"
t="$(lower .github/PULL_REQUEST_TEMPLATE.md)"
for k in "tests" "threat model" "mutation" "surviving" "bypass log" "constitution check"; do
  assert_contains "$t" "$k" "gate item '$k'"
done

test_case "CODEOWNERS has a catch-all entry"
assert_contains "$(cat .github/CODEOWNERS)" "* @raphaelpanta" "catch-all owner"

test_case "CONTRIBUTING.md documents the hook and squash merge"
c="$(cat CONTRIBUTING.md)"
assert_contains "$c" "core.hooksPath" "hook activation"
assert_contains "$(lower CONTRIBUTING.md)" "squash" "squash merge"
assert_contains "$c" "pull_request_target" "workflow safety rule"
assert_contains "$(lower CONTRIBUTING.md)" "gitleaks" "gitleaks installation"

test_case "issue chooser links to private vulnerability reporting"
assert_contains "$(cat .github/ISSUE_TEMPLATE/config.yml)" "blank_issues_enabled: true" "blank issues"
assert_contains "$(cat .github/ISSUE_TEMPLATE/config.yml)" "security/advisories/new" "advisory link"

test_case "no document contains a secret-shaped literal"
if grep -RIlE 'ghp_[A-Za-z0-9]{36}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----' \
  README.md CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md LICENSE .github >/dev/null 2>&1; then
  _fail "secret-shaped literal found in a community file"
fi

finish_tests
