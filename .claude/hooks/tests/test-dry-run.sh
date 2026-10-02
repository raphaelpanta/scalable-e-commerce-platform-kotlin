#!/usr/bin/env bash
# FR-017 / SC-006: HOOK_DRY_RUN=1 prints a DRY line per command, runs no tool, and exits 0 for every gate.
# The pull-request gate (.github/scripts/pr-gate.sh --dry-run) runs from a copy of the real scripts inside the
# fixture, so its gradlew is the shim and any execution would be recorded.
. "$(dirname "$0")/lib.sh"
new_fixture
export HOOK_DRY_RUN=1 FAKE_EXIT=1     # a real run would fail loudly
D="$T_ROOT/services/demo/domain"

run_hook post-edit-check.sh "$(post_edit_json "$D/src/main/kotlin/demo/Price.kt")"
assert_exit 0; assert_matches stdout '^DRY \[.*\]: .*gradlew -q --console=plain .*:services:demo:domain:test --tests \*\.PriceSpec$'
run_hook post-edit-check.sh "$(post_edit_json "$D/build.gradle.kts")"
assert_exit 0; assert_matches stdout '^DRY \[.*\]: .*gradlew .* help$'
run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/frontend/web/src/index.ts")"
assert_exit 0
for tool in eslint tsc vitest; do assert_contains stdout "npx --no-install $tool"; done

set_marker_now    # even a "nothing changed" marker must not hide the commands in a dry run
touch "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0
for l in "DRY [gradle:verify]" "DRY [web:lint]" "DRY [web:test]" "DRY [web:stryker]"; do assert_contains stdout "$l"; done
assert_not_contains stdout "DRY [gradle:pitest]"   # verify runs the full Pitest; the narrowed run is its fallback
assert_no_file "$(marker_path).new"

: >"$T_OUT/gate.log"
for args in "file services/demo/domain/src/main/kotlin/demo/Price.kt" "complete"; do
  # shellcheck disable=SC2086
  (cd "$T_ROOT" && "$HOOKS_DIR/run-gate.sh" $args) >"$T_OUT/stdout" 2>"$T_OUT/stderr" </dev/null; RC=$?
  assert_exit 0 "run-gate.sh $args"; assert_contains stdout "DRY ["
  [ ! -s "$T_OUT/stderr" ] || _t_fail "dry run wrote to stderr"
done
assert_no_invocations
assert_no_file "$T_ROOT/.claude/.cache/gate-timing.log"   # dry runs are not timed
assert_no_file "$T_ROOT/.claude/.cache/bypass.log"

mkdir -p "$T_ROOT/.github"; cp -R "$REPO_ROOT/.github/scripts" "$T_ROOT/.github/scripts"
rm -rf "$T_ROOT/.github/scripts/tests"
mkdir -p "$D/build/reports/pitest"
printf '<mutations>\n</mutations>\n' >"$D/build/reports/pitest/mutations.xml"
(cd "$T_ROOT" && "$HOOKS_DIR/run-gate.sh" pr) >"$T_OUT/stdout" 2>"$T_OUT/stderr" </dev/null; RC=$?
assert_exit 0 "run-gate.sh pr"
for s in verify mutation-gate surviving-mutants; do assert_contains stdout "DRY [$s]"; done
assert_contains stdout "./gradlew -q verify"
(cd "$T_ROOT" && .github/scripts/pr-gate.sh --dry-run) >"$T_OUT/stdout" 2>"$T_OUT/stderr" </dev/null
# shellcheck disable=SC2034  # RC is read by assert_exit
RC=$?
assert_exit 0 "pr-gate.sh --dry-run"; assert_contains stdout "DRY [mutation-gate]"
[ ! -s "$T_OUT/stderr" ] || _t_fail "pr dry run wrote to stderr: $(head -c 300 "$T_OUT/stderr")"
assert_no_invocations
test_done
