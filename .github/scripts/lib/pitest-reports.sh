# shellcheck shell=bash
# Shared helpers of the pull-request gate scripts (feature 001, user story 3): repository root, Pitest XML
# reports and the pull request description. Source it; it defines functions only and prints nothing.
# Portable: bash 3.2 (macOS) and Linux, POSIX awk and sed, no jq required in CI.

_PR_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# pr_root: repository root (PR_GATE_ROOT overrides it, used by the tests).
pr_root() {
  if [ -n "${PR_GATE_ROOT:-}" ]; then printf '%s\n' "$PR_GATE_ROOT"
  else (cd "$_PR_LIB_DIR/../../.." && pwd); fi
}

# is_ci: true on GitHub Actions or any CI that sets CI=true.
is_ci() { [ "${GITHUB_ACTIONS:-}" = "true" ] || [ "${CI:-}" = "true" ]; }

# pitest_reports ROOT: every <module>/build/reports/pitest/mutations.xml, sorted (build-logic test builds,
# node_modules and the .gradle cache are not modules of the repository).
pitest_reports() {
  find "$1" \( -name node_modules -o -name .gradle -o -name .git -o -path "$1/build-logic" \) -prune -o \
    -type f -path '*/build/reports/pitest/mutations.xml' -print 2>/dev/null | sort
}

# report_module_dir REPORT: the module directory of a report (strips /build/reports/pitest/mutations.xml).
report_module_dir() { printf '%s\n' "${1%/build/reports/pitest/mutations.xml}"; }

# module_gradle_path ROOT MODULE_DIR: ":services:catalog:domain" for ROOT/services/catalog/domain.
module_gradle_path() {
  local rel="${2#"$1"}"; rel="${rel#/}"
  printf ':%s\n' "$(printf '%s' "$rel" | tr '/' ':')"
}

# mutations_tsv REPORT: one line per mutation: status<TAB>detected<TAB>class<TAB>sourceFile<TAB>line<TAB>description.
# Pitest writes one <mutation> element per line; the input is re-split on </mutation> to be safe.
mutations_tsv() {
  tr '\n' ' ' <"$1" | sed 's#</mutation>#&\
#g' | awk -v q="'" '
    function attr(s, n,   r) { if (match(s, n "=.[^\"" q "]*")) { r = substr(s, RSTART + length(n) + 2, RLENGTH - length(n) - 2); return r } return "" }
    function tag(s, n,   a, b) { a = index(s, "<" n ">"); if (!a) return ""; s = substr(s, a + length(n) + 2); b = index(s, "</" n ">"); return b ? substr(s, 1, b - 1) : "" }
    function unxml(s) { gsub(/&lt;/, "<", s); gsub(/&gt;/, ">", s); gsub(/&quot;/, "\"", s); gsub(/&apos;/, q, s); gsub(/&amp;/, "\\&", s); gsub(/\t/, " ", s); return s }
    /<mutation[ >]/ {
      s = substr($0, index($0, "<mutation"))
      printf "%s\t%s\t%s\t%s\t%s\t%s\n", attr(s, "status"), attr(s, "detected"), tag(s, "mutatedClass"), tag(s, "sourceFile"), tag(s, "lineNumber"), unxml(tag(s, "description"))
    }'
}

# pr_body [BODY_FILE]: the pull request description with CR removed: from BODY_FILE, else $PR_BODY when set,
# else .pull_request.body of $GITHUB_EVENT_PATH (needs jq). Returns 1 when no source is available.
pr_body() {
  if [ -n "${1:-}" ]; then tr -d '\r' <"$1"; return 0; fi
  if [ "${PR_BODY+set}" = set ]; then printf '%s\n' "$PR_BODY" | tr -d '\r'; return 0; fi
  if [ -n "${GITHUB_EVENT_PATH:-}" ] && [ -f "$GITHUB_EVENT_PATH" ] && command -v jq >/dev/null 2>&1; then
    jq -r '.pull_request.body // ""' "$GITHUB_EVENT_PATH" | tr -d '\r'; return 0
  fi
  return 1
}

# body_section HEADING: the lines of the "## HEADING" section read on stdin (up to the next "## " heading),
# without HTML comments.
body_section() {
  awk -v h="$1" '
    $0 ~ "^##[ \t]+" h "[ \t]*$" { f = 1; next }
    f && /^##[ \t]/ { exit }
    f { print }' | sed 's/<!--.*-->//g' | awk '/<!--/ { c = 1 } !c { print } /-->/ { c = 0 }'
}

# json_flat FILE: "key value" per entry of a flat JSON object of integers (quality/mutation-baseline.json).
json_flat() {
  tr -d '\n\r' <"$1" | tr ',{}' '\n\n\n' |
    sed -n 's/^[[:space:]]*"\([^"]*\)"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1 \2/p'
}
