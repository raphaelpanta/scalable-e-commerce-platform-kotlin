#!/usr/bin/env bash
# US1 acceptance 4 / FR-009 / FR-010: ignored paths and missing tooling are silent no-ops.
. "$(dirname "$0")/lib.sh"
new_fixture
D="$T_ROOT/services/demo/domain"
for rel in build/Out.kt node_modules/pkg/index.ts .gradle/x.kts dist/bundle.ts .claude/hooks/x.kt .specify/x.kt \
           frontend/web/node_modules/lib/index.ts frontend/web/dist/index.ts; do
  mkdir -p "$T_ROOT/$(dirname "$rel")"; echo 'x' >"$T_ROOT/$rel"
  run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/$rel")"
  assert_exit 0 "$rel"; assert_silent
done
run_hook post-edit-check.sh "$(post_edit_json "$D/build/Gen.kt")"; assert_exit 0; assert_silent
echo 'x' >"$T_OUT/outside.kt"
run_hook post-edit-check.sh "$(post_edit_json "$T_OUT/outside.kt")"; assert_exit 0; assert_silent
run_hook post-edit-check.sh "$(post_edit_json "$D/src/main/kotlin/demo/DoesNotExist.kt")"; assert_exit 0; assert_silent
run_hook post-edit-check.sh '{}'; assert_exit 0; assert_silent
run_hook post-edit-check.sh 'not json'; assert_exit 0; assert_silent
assert_no_invocations

# no gradlew (and no gradle on PATH) -> silent exit 0
rm -f "$T_ROOT/gradlew"
run_hook post-edit-check.sh "$(post_edit_json "$D/src/main/kotlin/demo/Price.kt")"
assert_exit 0; assert_silent
run_hook post-edit-check.sh "$(post_edit_json "$D/build.gradle.kts")"
assert_exit 0; assert_silent

# no package.json -> silent exit 0
rm -f "$T_ROOT/frontend/web/package.json"
run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/frontend/web/src/index.ts")"
assert_exit 0; assert_silent
assert_no_invocations
test_done
