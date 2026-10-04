#!/usr/bin/env bash
# Tiny assertion helpers for the plain-Bash tests (bats is not installed).
# Source this file from a test: `source "$(dirname "$0")/lib/assert.sh"`.
# Failures print one line to stderr; passes are silent.

_T_COUNT=0
_T_FAIL=0
_T_CURRENT=""
_T_CASE_FAILED=0
LAST_OUT=""
LAST_RC=0

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "$TESTS_DIR/../.." && pwd)"
# shellcheck disable=SC2034  # read by the tests that source this file
STUB_GH="$TESTS_DIR/stubs/gh"

_TMP_DIRS=()
_cleanup_tmp() {
  local d
  for d in ${_TMP_DIRS[@]+"${_TMP_DIRS[@]}"}; do rm -rf "$d"; done
}
trap _cleanup_tmp EXIT

# mk_tmp: create a temporary directory removed when the test exits; prints its path.
mk_tmp() {
  local d
  d="$(mktemp -d "${TMPDIR:-/tmp}/e2e-test.XXXXXX")"
  d="$(cd "$d" && pwd -P)"
  _TMP_DIRS+=("$d")
  printf '%s\n' "$d"
}

# test_case NAME: start a named test case (counted once, failed at most once).
test_case() {
  _T_CURRENT="$1"
  _T_COUNT=$((_T_COUNT + 1))
  _T_CASE_FAILED=0
}

_fail() {
  printf 'FAIL [%s]: %s\n' "$_T_CURRENT" "$*" >&2
  if [ "$_T_CASE_FAILED" = 0 ]; then
    _T_CASE_FAILED=1
    _T_FAIL=$((_T_FAIL + 1))
  fi
}

assert_eq() { # expected actual [message]
  [ "$1" = "$2" ] || _fail "${3:-values differ}: expected '$1', got '$2'"
  return 0
}

assert_contains() { # haystack needle [message]
  case "$1" in
    *"$2"*) ;;
    *) _fail "${3:-missing text}: '$2' not found in: $(printf '%s' "$1" | head -c 600)" ;;
  esac
  return 0
}

assert_not_contains() { # haystack needle [message]
  case "$1" in
    *"$2"*) _fail "${3:-unexpected text}: '$2' found in: $(printf '%s' "$1" | head -c 600)" ;;
  esac
  return 0
}

assert_file_exists() { # path [message]
  [ -e "$1" ] || _fail "${2:-file missing}: $1"
  return 0
}

assert_file_missing() { # path [message]
  [ ! -e "$1" ] || _fail "${2:-file should not exist}: $1"
  return 0
}

# assert_exit_code EXPECTED CMD...: run CMD, capture merged output in LAST_OUT and status in LAST_RC.
assert_exit_code() {
  local expected="$1"
  shift
  LAST_RC=0
  LAST_OUT="$("$@" 2>&1)" || LAST_RC=$?
  [ "$LAST_RC" = "$expected" ] ||
    _fail "exit code of '$*': expected $expected, got $LAST_RC; output: $(printf '%s' "$LAST_OUT" | head -c 600)"
  return 0
}

# assert_nonzero CMD...: run CMD and require a non-zero status.
assert_nonzero() {
  LAST_RC=0
  LAST_OUT="$("$@" 2>&1)" || LAST_RC=$?
  [ "$LAST_RC" != 0 ] || _fail "expected failure from '$*'; output: $(printf '%s' "$LAST_OUT" | head -c 600)"
  return 0
}

# new_project DIR: copy the tooling and policy files of this repository into DIR (no .git).
new_project() {
  local dir="$1" item
  mkdir -p "$dir"
  for item in scripts .gitignore .gitleaks.toml .githooks .github LICENSE README.md CONTRIBUTING.md \
    CODE_OF_CONDUCT.md SECURITY.md; do
    [ -e "$PROJECT_ROOT/$item" ] && cp -R "$PROJECT_ROOT/$item" "$dir/"
  done
  mkdir -p "$dir/.specify/memory"
  [ -f "$PROJECT_ROOT/.specify/memory/constitution.md" ] &&
    cp "$PROJECT_ROOT/.specify/memory/constitution.md" "$dir/.specify/memory/"
  mkdir -p "$dir/specs"
  return 0
}

# git_identity: export a committer identity so tests never depend on global git config.
git_identity() {
  export GIT_AUTHOR_NAME="Test Maintainer" GIT_AUTHOR_EMAIL="test@example.invalid"
  export GIT_COMMITTER_NAME="Test Maintainer" GIT_COMMITTER_EMAIL="test@example.invalid"
}

# finish_tests: report counts to run-all.sh (or print them) and exit non-zero on any failure.
finish_tests() {
  if [ -n "${TEST_COUNT_FILE:-}" ]; then
    printf '%s %s\n' "$_T_COUNT" "$_T_FAIL" >"$TEST_COUNT_FILE"
  else
    printf '%s tests, %s failed\n' "$_T_COUNT" "$_T_FAIL"
  fi
  [ "$_T_FAIL" = 0 ]
}
