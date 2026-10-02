#!/usr/bin/env bash
# Constitution test: no Sync Impact Report review scratch is published (FR-003).
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$PROJECT_ROOT/scripts/lib/common.sh"

doc="$PROJECT_ROOT/.specify/memory/constitution.md"

test_case "real constitution has no Sync Impact Report text"
assert_file_exists "$doc"
assert_eq "0" "$(grep -c 'Sync Impact Report' "$doc")" "Sync Impact Report occurrences"

test_case "real constitution has no HTML comment before its first heading"
head_part="$(awk '/^#/ {exit} {print}' "$doc")"
assert_not_contains "$head_part" "<!--" "comment opener before first heading"
assert_eq "# Scalable E-Commerce Platform Constitution" "$(grep -m1 '^# ' "$doc")" "first heading"
assert_contains "$(tail -n 1 "$doc")" "**Version**: 1.1.0" "version line"

tmp="$(mk_tmp)"

test_case "guarded removal strips the Sync Impact Report comment and keeps the body"
printf '<!--\nSync Impact Report\n==================\nscratch\n-->\n\n# Title\n\nBody <!-- inline note -->\n' >"$tmp/a.md"
strip_sync_impact_report "$tmp/a.md"
assert_eq "$(printf '\n# Title\n\nBody <!-- inline note -->')" "$(cat "$tmp/a.md")" "stripped fixture"

test_case "guarded removal leaves an unrelated leading comment untouched"
printf '<!-- keep me: license header -->\n# Title\ntext\n' >"$tmp/b.md"
cp "$tmp/b.md" "$tmp/b.orig"
strip_sync_impact_report "$tmp/b.md"
assert_eq "$(cat "$tmp/b.orig")" "$(cat "$tmp/b.md")" "unrelated comment preserved"

test_case "guarded removal is a no-op on a clean file (idempotent)"
printf '# Title\n\nSync Impact Report is mentioned in prose after the heading.\n' >"$tmp/c.md"
cp "$tmp/c.md" "$tmp/c.orig"
strip_sync_impact_report "$tmp/c.md"
strip_sync_impact_report "$tmp/c.md"
assert_eq "$(cat "$tmp/c.orig")" "$(cat "$tmp/c.md")" "clean file unchanged"

finish_tests
