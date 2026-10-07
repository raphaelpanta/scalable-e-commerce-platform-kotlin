#!/usr/bin/env bash
# Run every scripts/tests/test_*.sh quietly. Prints failures only, then one summary line (`--quiet` drops the
# summary on success, for `./gradlew -q verify`). The files are independent (each works in its own temporary
# directories), so they run concurrently; the output is reported in file order. SCRIPT_TESTS_JOBS=1 runs them one
# after the other.
set -euo pipefail

quiet=0
[ "${1:-}" != --quiet ] || quiet=1
here="$(cd "$(dirname "$0")" && pwd)"
total=0
failed=0
tmp="$(mktemp -d "${TMPDIR:-/tmp}/run-all.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

shopt -s nullglob
files=("$here"/test_*.sh)
[ "${#files[@]}" -gt 0 ] || { echo "FAIL: no test files found in $here" >&2; exit 1; }

run_one() { # INDEX FILE: runs one test file, leaving out, rc and counts files in $tmp
  local i="$1" f="$2" rc=0
  : >"$tmp/$i.counts"
  TEST_COUNT_FILE="$tmp/$i.counts" bash "$f" >"$tmp/$i.out" 2>&1 || rc=$?
  printf '%s\n' "$rc" >"$tmp/$i.rc"
}

i=0
for f in "${files[@]}"; do
  if [ "${SCRIPT_TESTS_JOBS:-0}" = 1 ]; then
    run_one "$i" "$f"
  else
    run_one "$i" "$f" &
  fi
  i=$((i + 1))
done
wait

i=0
for f in "${files[@]}"; do
  rc="$(cat "$tmp/$i.rc" 2>/dev/null || echo 1)"
  read -r n bad <"$tmp/$i.counts" 2>/dev/null || { n=0; bad=0; }
  n="${n:-0}"; bad="${bad:-0}"
  total=$((total + n))
  if [ "$rc" != 0 ]; then
    [ "$bad" -gt 0 ] || bad=1
    failed=$((failed + bad))
    printf '%s\n' "--- $(basename "$f") (exit $rc)" >&2
    head -n 40 "$tmp/$i.out" >&2
  fi
  i=$((i + 1))
done

if [ "$failed" -gt 0 ]; then
  printf 'FAIL: %s of %s tests failed\n' "$failed" "$total" >&2
  exit 1
fi
[ "$quiet" = 1 ] || printf 'PASS: %s tests\n' "$total"
