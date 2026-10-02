#!/usr/bin/env bash
# Dry-run test: --dry-run is read-only, deterministic, parameterisable and stops early on bad preconditions.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"

fresh() { # creates $P (project copy, no .git) and $S (stub state)
  local t
  t="$(mk_tmp)"
  P="$t/proj"
  S="$t/state"
  new_project "$P"
  mkdir -p "$S"
  export GH_BIN="$P/scripts/tests/stubs/gh" GH_STUB_STATE="$S"
  unset GH_STUB_FAIL_ON GH_STUB_REMOTE_URL REPO_REMOTE_URL
}
in_proj() { (cd "$P" && "$@"); }
writes() { grep -c '^WRITE' "$S/calls.log" 2>/dev/null || true; }

test_case "--help exits 0 and lists the flags"
fresh
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --help
for f in --dry-run --yes --owner --name --require-check --admin-bypass --verbose; do
  assert_contains "$LAST_OUT" "$f" "help lists $f"
done

test_case "dry run creates no .git, makes no write call and prefixes every mutation"
fresh
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --dry-run
out="$LAST_OUT"
assert_file_missing "$P/.git" "dry run must not create .git"
assert_eq "0" "$(writes)" "write calls recorded by the stub"
assert_file_exists "$S/calls.log" "reads were made"
for needle in \
  "DRY-RUN: git init -q -b main" \
  "DRY-RUN: git config core.hooksPath .githooks" \
  "DRY-RUN: git add -A" \
  "DRY-RUN: git commit" \
  "DRY-RUN: gh repo create raphaelpanta/scalable-e-commerce-platform-kotlin --public" \
  "DRY-RUN: git push -u origin main" \
  "DRY-RUN: gh api -X PUT repos/raphaelpanta/scalable-e-commerce-platform-kotlin/topics" \
  "ecommerce" "kafka" "hexagonal-architecture" \
  "secret_scanning_push_protection" \
  "DRY-RUN: gh api -X PUT repos/raphaelpanta/scalable-e-commerce-platform-kotlin/private-vulnerability-reporting" \
  "allow_merge_commit" \
  "DRY-RUN: gh api -X PUT repos/raphaelpanta/scalable-e-commerce-platform-kotlin/branches/main/protection" \
  "DRY-RUN: scripts/verify-repo.sh"; do
  assert_contains "$out" "$needle" "dry-run output"
done

test_case "dry run is identical on repeat"
first="$out"
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --dry-run
assert_eq "$first" "$LAST_OUT" "second dry run output"

test_case "--owner and --name overrides are honoured (second repository, FR-013)"
fresh
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --dry-run --owner another-owner --name another-repo
assert_contains "$LAST_OUT" "gh repo create another-owner/another-repo --public" "overridden target"
assert_not_contains "$LAST_OUT" "scalable-e-commerce-platform-kotlin" "default name must not leak"
assert_eq "0" "$(writes)" "write calls"

test_case "--require-check and --admin-bypass appear in the protection step"
fresh
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --dry-run --require-check verify --admin-bypass
assert_contains "$LAST_OUT" '"contexts":["verify"]' "required check context"
assert_contains "$LAST_OUT" '"enforce_admins":false' "admin bypass"
assert_contains "$LAST_OUT" "admin bypass" "bypass is logged"

test_case "signed out: stops before any change"
fresh
touch "$S/signed_out"
assert_nonzero in_proj scripts/bootstrap-repo.sh --dry-run
assert_not_contains "$LAST_OUT" "DRY-RUN" "no step planned when signed out"
assert_contains "$LAST_OUT" "gh auth login" "remedy"
assert_file_missing "$P/.git"

test_case "repository name already taken by a foreign repository: stops before any change"
fresh
"$GH_BIN" repo create raphaelpanta/scalable-e-commerce-platform-kotlin --public --description x >/dev/null
jq '.size = 120' "$S/repo.json" >"$S/repo.tmp" && mv "$S/repo.tmp" "$S/repo.json"
assert_nonzero in_proj scripts/bootstrap-repo.sh --dry-run
assert_contains "$LAST_OUT" "already exists" "name taken message"
assert_not_contains "$LAST_OUT" "DRY-RUN" "no step planned"
assert_file_missing "$P/.git"

test_case "constitution still carrying the Sync Impact Report: refuses before any change"
fresh
printf '<!--\nSync Impact Report\n-->\n\n# Constitution\n' >"$P/.specify/memory/constitution.md"
assert_nonzero in_proj scripts/bootstrap-repo.sh --dry-run
assert_contains "$LAST_OUT" "Sync Impact Report" "refusal reason"
assert_not_contains "$LAST_OUT" "DRY-RUN" "no step planned"

test_case "invalid owner or name is rejected"
fresh
assert_nonzero in_proj scripts/bootstrap-repo.sh --dry-run --name 'bad name;rm -rf'
assert_nonzero in_proj scripts/bootstrap-repo.sh --dry-run --bogus-flag

finish_tests
