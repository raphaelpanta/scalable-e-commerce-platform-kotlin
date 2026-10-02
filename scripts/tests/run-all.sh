#!/usr/bin/env bash
# Run every scripts/tests/test_*.sh quietly. Prints failures only, then one summary line.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
total=0
failed=0
tmp="$(mktemp -d "${TMPDIR:-/tmp}/run-all.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

shopt -s nullglob
files=("$here"/test_*.sh)
[ "${#files[@]}" -gt 0 ] || { echo "FAIL: no test files found in $here" >&2; exit 1; }

for f in "${files[@]}"; do
  counts="$tmp/counts"
  : >"$counts"
  rc=0
  out="$(TEST_COUNT_FILE="$counts" bash "$f" 2>&1)" || rc=$?
  read -r n bad <"$counts" 2>/dev/null || { n=0; bad=0; }
  n="${n:-0}"; bad="${bad:-0}"
  total=$((total + n))
  if [ "$rc" != 0 ]; then
    [ "$bad" -gt 0 ] || bad=1
    failed=$((failed + bad))
    printf '%s\n' "--- $(basename "$f") (exit $rc)" >&2
    printf '%s\n' "$out" | head -n 40 >&2
  fi
done

if [ "$failed" -gt 0 ]; then
  printf 'FAIL: %s of %s tests failed\n' "$failed" "$total"
  exit 1
fi
printf 'PASS: %s tests\n' "$total"
