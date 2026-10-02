#!/usr/bin/env bash
# Pull-request gate, mutation threshold and ratchet (FR-015). For every Pitest report
# <module>/build/reports/pitest/mutations.xml (written by the full Pitest run inside ./gradlew -q verify):
#  - score = detected / (all mutants except NON_VIABLE), rounded like Pitest; a module without mutants passes;
#  - fails when the score is below the module threshold: `pitest { mutationThreshold.set(N) }` in its
#    build.gradle.kts, default 80, never below 80 (QualityThresholds.MINIMUM_MUTATION_THRESHOLD);
#  - fails when the score is below the module's entry in quality/mutation-baseline.json (the ratchet);
#  - with a base ref (--base-ref REF or $PR_BASE_SHA) fails when an entry of the base branch's baseline was
#    lowered or removed: the ratchet may only go up.
# Silent on success; one line per violation on stdout and exit 1 otherwise.
# Usage: mutation-gate.sh [--dry-run] [--base-ref REF]    (HOOK_DRY_RUN=1 behaves like --dry-run)
set -uo pipefail
# shellcheck source=lib/pitest-reports.sh
. "$(dirname "$0")/lib/pitest-reports.sh"

DEFAULT_THRESHOLD=80
BASELINE_REL="quality/mutation-baseline.json"
DRY="${HOOK_DRY_RUN:-}"; BASE_REF="${PR_BASE_SHA:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --base-ref) [ $# -ge 2 ] || { echo "Usage: mutation-gate.sh [--dry-run] [--base-ref REF]" >&2; exit 64; }; BASE_REF="$2"; shift ;;
    *) echo "Usage: mutation-gate.sh [--dry-run] [--base-ref REF]" >&2; exit 64 ;;
  esac
  shift
done
ROOT="$(pr_root)"
BASELINE="$ROOT/$BASELINE_REL"

if [ -n "$DRY" ]; then
  echo "DRY [mutation-gate]: score of every */build/reports/pitest/mutations.xml >= module threshold (default $DEFAULT_THRESHOLD) and >= $BASELINE_REL${BASE_REF:+; $BASELINE_REL not lowered against $BASE_REF}"
  exit 0
fi

fail=0
violation() { echo "$1"; fail=1; }
baseline_of() { [ -f "$BASELINE" ] && json_flat "$BASELINE" | awk -v k="$1" '$1 == k { print $2; exit }'; }

while IFS= read -r report; do
  [ -n "$report" ] || continue
  mod="$(report_module_dir "$report")"
  path="$(module_gradle_path "$ROOT" "$mod")"
  # shellcheck disable=SC2046  # intentional word splitting of two awk-printed numbers
  set -- $(mutations_tsv "$report" | awk -F '\t' '$1 != "NON_VIABLE" { t++; if ($2 == "true") d++ } END { print d + 0, t + 0 }')
  detected="$1"; total="$2"
  [ "$total" -gt 0 ] || continue   # no mutants (e.g. only comments changed): nothing to score
  score=$(( (detected * 200 + total) / (2 * total) ))
  threshold="$(sed -n 's/.*mutationThreshold\.set([[:space:]]*\([0-9][0-9]*\)[[:space:]]*).*/\1/p' "$mod/build.gradle.kts" 2>/dev/null | tail -n 1)"
  [ -n "$threshold" ] && [ "$threshold" -ge "$DEFAULT_THRESHOLD" ] || threshold="$DEFAULT_THRESHOLD"
  if [ "$score" -lt "$threshold" ]; then
    violation "module $path: score $score% < threshold $threshold%"
  fi
  base="$(baseline_of "$path")"
  if [ -n "$base" ] && [ "$score" -lt "$base" ]; then
    violation "module $path: score $score% < baseline $base% ($BASELINE_REL)"
  fi
done <<<"$(pitest_reports "$ROOT")"

# Ratchet: the committed baseline may only increase relative to the base branch copy.
if [ -n "$BASE_REF" ]; then
  if git -C "$ROOT" rev-parse -q --verify "$BASE_REF^{commit}" >/dev/null 2>&1; then
    tmp="$(mktemp)"
    if git -C "$ROOT" show "$BASE_REF:$BASELINE_REL" >"$tmp" 2>/dev/null; then
      while read -r key old; do
        [ -n "$key" ] || continue
        new="$(baseline_of "$key")"
        if [ -z "$new" ]; then
          rel="$(printf '%s' "${key#:}" | tr ':' '/')"
          [ -d "$ROOT/$rel" ] && violation "baseline $key: removed (was $old on the base branch)"
        elif [ "$new" -lt "$old" ]; then
          violation "baseline $key: $new lowered from $old on the base branch ($BASELINE_REL may only increase)"
        fi
      done <<<"$(json_flat "$tmp")"
    fi
    rm -f "$tmp"
  elif is_ci; then
    violation "cannot resolve the base ref $BASE_REF to check that $BASELINE_REL only increases"
  fi
fi
exit "$fail"
