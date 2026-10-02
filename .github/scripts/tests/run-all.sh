#!/usr/bin/env bash
# Runs every .github/scripts/tests/test-*.sh (pull-request gate scripts, feature 001 user story 3).
# Silent on success; prints only the names of failing tests (VERBOSE=1 adds their output) and exits non-zero
# when any test fails.
set -uo pipefail
dir="$(cd "$(dirname "$0")" && pwd)"
failed=0
for t in "$dir"/test-*.sh; do
  [ -e "$t" ] || continue
  out="$(bash "$t" 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ]; then
    failed=1
    echo "FAIL: $(basename "$t")"
    [ -n "${VERBOSE:-}" ] && printf '%s\n' "$out" | sed 's/^/    /'
  fi
done
exit "$failed"
