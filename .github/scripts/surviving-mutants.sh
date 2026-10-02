#!/usr/bin/env bash
# Pull-request gate, surviving mutants on changed lines (FR-016). Every undetected mutant (SURVIVED or
# NO_COVERAGE) in the Pitest reports whose source line was added by the pull request must be justified in the
# pull request description, one line per mutant under the heading "## Mutant justifications":
#     services/<svc>/<layer>/src/main/kotlin/<package path>/<File>.kt:<line> <reason>
# (a list marker and backticks around path:line are allowed; the reason must not be empty). Killed mutants and
# mutants on unchanged lines are ignored. Changed lines come from `git diff -U0 <base>...HEAD`; the
# description is read from the event payload ($GITHUB_EVENT_PATH) or $PR_BODY, without network access.
# Silent on success; the unjustified mutants on stdout and exit 1 otherwise.
# Usage: surviving-mutants.sh [--dry-run] [--base-ref REF] [--diff FILE] [--body-file FILE]
#   --base-ref  base of the diff (default $PR_BASE_SHA, else origin/main); without a resolvable base the
#               check is skipped locally and fails in CI
#   --diff      a unified diff with -U0 hunks instead of running git (tests)
#   --body-file the pull request description from a file (tests, local rehearsal)
set -uo pipefail
# shellcheck source=lib/pitest-reports.sh
. "$(dirname "$0")/lib/pitest-reports.sh"

usage() { echo "Usage: surviving-mutants.sh [--dry-run] [--base-ref REF] [--diff FILE] [--body-file FILE]" >&2; exit 64; }
DRY="${HOOK_DRY_RUN:-}"; BASE_REF="${PR_BASE_SHA:-}"; DIFF_FILE=""; BODY_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --base-ref) [ $# -ge 2 ] || usage; BASE_REF="$2"; shift ;;
    --diff) [ $# -ge 2 ] || usage; DIFF_FILE="$2"; shift ;;
    --body-file) [ $# -ge 2 ] || usage; BODY_FILE="$2"; shift ;;
    *) usage ;;
  esac
  shift
done
ROOT="$(pr_root)"
BASE_REF="${BASE_REF:-origin/main}"

if [ -n "$DRY" ]; then
  echo "DRY [surviving-mutants]: SURVIVED/NO_COVERAGE mutants of */build/reports/pitest/mutations.xml on lines added by ${DIFF_FILE:-git diff -U0 $BASE_REF...HEAD} need 'path:line reason' under '## Mutant justifications'"
  exit 0
fi

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# 1. Lines added by the pull request: path:line, one per line.
if [ -n "$DIFF_FILE" ]; then
  cat "$DIFF_FILE" >"$TMP/diff"
elif git -C "$ROOT" rev-parse -q --verify "$BASE_REF^{commit}" >/dev/null 2>&1 &&
  git -C "$ROOT" diff -U0 --no-color --no-ext-diff "$BASE_REF...HEAD" >"$TMP/diff" 2>/dev/null; then
  :
else
  if is_ci; then echo "surviving-mutants: cannot compute the changed lines: base $BASE_REF is not available (fetch-depth 0?)"; exit 1; fi
  exit 0   # local run outside a branch with a base: nothing to compare against
fi
awk '
  /^\+\+\+ / { f = substr($0, 5); sub(/^b\//, "", f); sub(/\t.*$/, "", f); if (f == "/dev/null") f = ""; next }
  /^@@ / && f != "" {
    h = $0; sub(/^@@ -[0-9,]+ \+/, "", h); sub(/ .*/, "", h)
    n = split(h, p, ","); start = p[1] + 0; count = (n > 1) ? p[2] + 0 : 1
    for (i = 0; i < count; i++) print f ":" (start + i)
  }' "$TMP/diff" | sort -u >"$TMP/added"
[ -s "$TMP/added" ] || exit 0

# 2. Undetected mutants mapped to repository paths: path:line<TAB>status description (class).
source_path() { # MODULE_REL CLASS SOURCE_FILE
  local pkg="${2%%\$*}" cand d
  case "$pkg" in *.*) pkg="${pkg%.*}" ;; *) pkg="" ;; esac
  for d in kotlin java; do
    cand="$1/src/main/$d${pkg:+/$(printf '%s' "$pkg" | tr . /)}/$3"
    [ -f "$ROOT/$cand" ] && { echo "$cand"; return; }
  done
  cand="$(cd "$ROOT" && find "$1/src/main" -type f -name "$3" 2>/dev/null | head -n 1)"
  echo "${cand:-$1/src/main/kotlin${pkg:+/$(printf '%s' "$pkg" | tr . /)}/$3}"
}
: >"$TMP/undetected"
while IFS= read -r report; do
  [ -n "$report" ] || continue
  mod="$(report_module_dir "$report")"; modrel="${mod#"$ROOT"/}"
  mutations_tsv "$report" | awk -F '\t' '$1 == "SURVIVED" || $1 == "NO_COVERAGE"' >"$TMP/m"
  while IFS="$(printf '\t')" read -r status _detected class src line desc; do
    printf '%s:%s\t%s %s (%s)\n' "$(source_path "$modrel" "$class" "$src")" "$line" "$status" "$desc" "$class"
  done <"$TMP/m" >>"$TMP/undetected"
done <<<"$(pitest_reports "$ROOT")"

# 3. Keep the undetected mutants on added lines, one per path:line.
awk -F '\t' 'FILENAME == ARGV[1] { added[$0] = 1; next } ($1 in added) && !seen[$1]++' "$TMP/added" "$TMP/undetected" >"$TMP/onchanged"
[ -s "$TMP/onchanged" ] || exit 0

# 4. Justifications from the description.
if pr_body "$BODY_FILE" >"$TMP/body" 2>/dev/null; then :; else : >"$TMP/body"; fi
body_section "Mutant justifications" <"$TMP/body" |
  sed -n 's/^[[:space:]]*\([-*][[:space:]][[:space:]]*\)\{0,1\}`\{0,1\}\([^[:space:]`]*:[0-9][0-9]*\)`\{0,1\}[[:space:]][[:space:]]*[^[:space:]].*$/\2/p' |
  sort -u >"$TMP/justified"
awk -F '\t' 'FILENAME == ARGV[1] { ok[$0] = 1; next } !($1 in ok)' "$TMP/justified" "$TMP/onchanged" >"$TMP/unjustified"
[ -s "$TMP/unjustified" ] || exit 0

echo "Surviving mutants on changed lines without a justification:"
awk -F '\t' '{ print "  " $1 " " $2 }' "$TMP/unjustified"
echo "Kill each one with a test, or add a line per mutant under \"## Mutant justifications\" in the pull request description:"
awk -F '\t' '{ print "  " $1 " <why this mutant is acceptable>" }' "$TMP/unjustified"
exit 1
