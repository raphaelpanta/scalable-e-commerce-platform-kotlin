#!/usr/bin/env bash
# Community-files test: recognised paths and required content (FR-006, FR-007, FR-008).
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"

cd "$PROJECT_ROOT" || exit 1
lower() { tr '[:upper:]' '[:lower:]' <"$1"; }

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
