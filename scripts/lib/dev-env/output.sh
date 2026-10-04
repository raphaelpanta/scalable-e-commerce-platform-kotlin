# shellcheck shell=bash
# Output formats of scripts/dev-env.sh (sourced, never executed). Check lines, step lines, fix and note lines and
# the summary follow specs/005-storefront-dev-bootstrap/contracts/dev-env-cli.md to the character; colour is
# added only on a terminal without NO_COLOR, around the status word, after padding.
# Bash 3.2 compatible.

COLOR=0
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then COLOR=1; fi

CHECK_TOTAL=0
CHECK_PASS=0
CHECK_FAIL=0
CHECK_SKIP=0
FAILED_CHECKS=""

reset_check_counts() {
  CHECK_TOTAL=0
  CHECK_PASS=0
  CHECK_FAIL=0
  CHECK_SKIP=0
  FAILED_CHECKS=""
}

# colour STATUS: ANSI colour for a status word (empty without colour).
colour() {
  [ "$COLOR" = 1 ] || return 0
  case "$1" in
    PASS | OK) printf '\033[32m' ;;
    FAIL) printf '\033[31m' ;;
    SKIP | DRY-RUN) printf '\033[33m' ;;
    CHANGE | INSTALL) printf '\033[36m' ;;
  esac
}

# emit_status_line STATUS LINE: print LINE, whose first word is STATUS, with the status coloured when enabled.
emit_status_line() {
  local status="$1" line="$2" c
  c="$(colour "$status")"
  if [ -n "$c" ]; then
    printf '%s%s\033[0m%s\n' "$c" "$status" "${line#"$status"}"
  else
    printf '%s\n' "$line"
  fi
}

# check_line STATUS NAME FOUND EXPECTED: one check result (`found none` when FOUND is empty).
check_line() {
  local status="$1" name="$2" found="$3" expected="$4" line
  [ -n "$found" ] || found=none
  line="$(printf '%-4s  %-10s %-12s   %s' "$status" "$name" "found $found" "expected $expected")"
  emit_status_line "$status" "$line"
  CHECK_TOTAL=$((CHECK_TOTAL + 1))
  case "$status" in
    PASS) CHECK_PASS=$((CHECK_PASS + 1)) ;;
    FAIL) CHECK_FAIL=$((CHECK_FAIL + 1)); FAILED_CHECKS="$FAILED_CHECKS $name" ;;
  esac
}

# skip_line NAME REASON: `SKIP  <name>      (<reason>)`.
skip_line() {
  local line
  line="$(printf '%-4s  %-10s (%s)' SKIP "$1" "$2")"
  emit_status_line SKIP "$line"
  CHECK_TOTAL=$((CHECK_TOTAL + 1))
  CHECK_SKIP=$((CHECK_SKIP + 1))
}

# fix_line OS COMMAND: remediation under a FAIL, one line per alternative.
fix_line() { printf '      fix (%s): %s\n' "$1" "$2"; }

# note_line TEXT: advice that is not a failure.
note_line() { printf '      note: %s\n' "$1"; }

# summary_line: last line of the check phase.
summary_line() {
  printf 'checks: %s total, %s PASS, %s FAIL, %s SKIP\n' "$CHECK_TOTAL" "$CHECK_PASS" "$CHECK_FAIL" "$CHECK_SKIP"
}

# step_line STATUS ID TEXT: one configuration step (OK, CHANGE, SKIP, DRY-RUN, FAIL).
step_line() {
  local line
  line="$(printf '%-7s %-10s %s' "$1" "$2" "$3")"
  emit_status_line "$1" "$line"
}

# dry_run_line WHAT: the mutation a dry run skipped.
dry_run_line() { printf 'DRY-RUN: %s\n' "$*"; }

# usage_error MESSAGE: usage on stderr, exit 2.
usage_error() {
  printf 'ERROR: %s\n' "$*" >&2
  usage >&2
  exit 2
}

# error MESSAGE: diagnostic on stderr (no exit).
error() { printf 'ERROR: %s\n' "$*" >&2; }
