#!/usr/bin/env bash
# Golden dry-run output of both hooks against the fixture (T005). Guards the Phase 2 refactor:
# the hooks must keep printing exactly these commands. Regenerate deliberately with UPDATE_GOLDEN=1
# when a later task changes the commands on purpose (new tasks, new flags). The complete gate now runs
# `gradle:verify` (feature 002's root lifecycle task, full Pitest included) instead of `check`; the incremental
# `gradle:pitest` run of T032 is only its fallback for a build without `verify` (test-stop-mutation.sh), so it
# is not in this dry run. The rest is still the Phase 2 baseline.
. "$(dirname "$0")/lib.sh"
new_fixture
export HOOK_DRY_RUN=1
GOLDEN="$T_DIR/fixtures/expected/baseline-dry-run.txt"
ACTUAL="$T_OUT/golden.actual"

scenario() { # NAME SCRIPT JSON
  { echo "== $1 =="; printf '%s' "$3" | "$HOOKS_DIR/$2" 2>&1 | sed "s#$T_ROOT#<ROOT>#g"; echo "exit=${PIPESTATUS[1]}"; } >>"$ACTUAL"
}
: >"$ACTUAL"
scenario "post-edit Price.kt"          post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt")"
scenario "post-edit PriceSpec.kt"      post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/domain/src/test/kotlin/demo/PriceSpec.kt")"
scenario "post-edit Repo.kt"           post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/infrastructure/src/main/kotlin/demo/Repo.kt")"
scenario "post-edit domain build.gradle.kts" post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/domain/build.gradle.kts")"
scenario "post-edit index.ts"          post-edit-check.sh "$(post_edit_json "$T_ROOT/frontend/web/src/index.ts")"
scenario "stop (complete gate)"        stop-full-check.sh "$(stop_json false)"

if [ -n "${UPDATE_GOLDEN:-}" ]; then cp "$ACTUAL" "$GOLDEN"; fi
if ! diff -u "$GOLDEN" "$ACTUAL" >"$T_OUT/golden.diff"; then
  _t_fail "dry-run output differs from $GOLDEN"; head -n 40 "$T_OUT/golden.diff"
fi
assert_no_invocations
test_done
