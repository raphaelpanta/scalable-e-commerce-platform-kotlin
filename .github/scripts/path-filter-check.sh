#!/usr/bin/env bash
# T108 (feature 004): which path-filtered workflows would a change trigger? Reads the `paths:` lists of the workflows
# in .github/workflows (the per-service callers and platform.yml; workflows without `paths:` such as verify.yml and
# pr-gate.yml always run and are not listed) and prints, one per line and sorted, the workflow files that have at
# least one `paths:` pattern matching at least one changed file. Glob rules as in GitHub Actions: `*` matches any
# characters except `/`, `**` matches anything including `/`, `?` one character except `/`. Negated patterns (`!`)
# and `paths-ignore` are not supported (none of the workflows uses them).
#
# Usage: path-filter-check.sh [--workflows DIR] [--base REF] [FILE...]
#   FILE...       changed files, relative to the repository root; `-` reads them from stdin, one per line
#   --base REF    use `git diff --name-only REF...HEAD` as the changed files (in addition to FILE...)
#   --workflows   directory with the workflows (default: .github/workflows next to this script)
# Examples:
#   .github/scripts/path-filter-check.sh services/cart/domain/src/main/kotlin/Cart.kt   # -> cart.yml
#   git diff --name-only origin/main...HEAD | .github/scripts/path-filter-check.sh -
#   .github/scripts/path-filter-check.sh --base origin/main
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOWS="$HERE/../workflows"
BASE=""
FILES=()

usage() { sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//' >&2; exit 64; }
while [ $# -gt 0 ]; do
  case "$1" in
    --workflows) [ $# -ge 2 ] || usage; WORKFLOWS="$2"; shift ;;
    --base) [ $# -ge 2 ] || usage; BASE="$2"; shift ;;
    -h | --help) usage ;;
    -) while IFS= read -r line; do [ -z "$line" ] || FILES+=("$line"); done ;;
    -*) usage ;;
    *) FILES+=("$1") ;;
  esac
  shift
done
if [ -n "$BASE" ]; then
  while IFS= read -r line; do [ -z "$line" ] || FILES+=("$line"); done < <(git diff --name-only "$BASE...HEAD")
fi
[ "${#FILES[@]}" -gt 0 ] || usage
[ -d "$WORKFLOWS" ] || { echo "no such directory: $WORKFLOWS" >&2; exit 66; }

changed="$(mktemp)"
trap 'rm -f "$changed"' EXIT
printf '%s\n' "${FILES[@]}" >"$changed"

for wf in "$WORKFLOWS"/*.yml "$WORKFLOWS"/*.yaml; do
  [ -f "$wf" ] || continue
  awk -v changed="$changed" '
    function glob2re(g,   i, c, re) {
      re = "^"
      for (i = 1; i <= length(g); i++) {
        c = substr(g, i, 1)
        if (c == "*") {
          if (substr(g, i + 1, 1) == "*") { re = re ".*"; i++ } else re = re "[^/]*"
        } else if (c == "?") re = re "[^/]"
        else if (index(".()+{}[]|^$\\", c) > 0) re = re "\\" c
        else re = re c
      }
      return re "$"
    }
    # a `paths:` key opens a block list; it ends at the first line that is not a list item
    /^[ ]+paths:[ ]*$/ { inpaths = 1; next }
    inpaths && /^[ ]+-[ ]+/ {
      p = $0
      sub(/^[ ]+-[ ]+/, "", p)
      sub(/[ ]+#.*$/, "", p)
      gsub(/^["\047]|["\047]$/, "", p)
      patterns[++n] = p
      next
    }
    { inpaths = 0 }
    END {
      if (n == 0) exit 1
      while ((getline f < changed) > 0)
        for (i = 1; i <= n; i++) if (f ~ glob2re(patterns[i])) exit 0
      exit 1
    }
  ' "$wf" && basename "$wf"
done | sort -u
exit 0
