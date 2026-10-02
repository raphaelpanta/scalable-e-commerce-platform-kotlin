#!/usr/bin/env bash
# FR-014 / FR-017: pr-gate.sh runs verify (full Pitest included), the mutation gate and the surviving-mutant
# check in that order, stops at the first failure with a bounded report, writes a short step summary, and
# --dry-run (or HOOK_DRY_RUN=1) prints DRY lines without running anything.
. "$(dirname "$0")/lib.sh"

fake_gradlew() {
  cat >"$T_ROOT/gradlew" <<'SH'
#!/usr/bin/env bash
echo "gradlew $*" >>"$CALLS"
i=0; while [ "$i" -lt "${FAKE_OUTPUT_LINES:-0}" ]; do i=$((i + 1)); echo "fake gradle line $i"; done
exit "${FAKE_EXIT:-0}"
SH
  chmod +x "$T_ROOT/gradlew"
  export CALLS="$T_OUT/calls.log"; : >"$CALLS"
  unset FAKE_EXIT FAKE_OUTPUT_LINES
}

# green run: silent, verify ran without a class filter, summary written
new_repo domain-ok; fake_gradlew
export GITHUB_STEP_SUMMARY="$T_OUT/summary.md"
run_script pr-gate.sh --base-ref no-such-ref
assert_exit 0; assert_silent
grep -qx 'gradlew -q verify' "$CALLS" || _t_fail "verify not invoked as './gradlew -q verify': $(cat "$CALLS")"
assert_not_contains "$CALLS" "harness.mutation.classes"
assert_contains "$GITHUB_STEP_SUMMARY" "| verify | pass |"
assert_contains "$GITHUB_STEP_SUMMARY" "| mutation-gate | pass |"
assert_contains "$GITHUB_STEP_SUMMARY" "| surviving-mutants | pass |"
[ "$(wc -l <"$GITHUB_STEP_SUMMARY")" -le 20 ] || _t_fail "summary longer than 20 lines"

# verify fails: bounded report (60 lines), later steps not run
: >"$GITHUB_STEP_SUMMARY"
FAKE_EXIT=1 FAKE_OUTPUT_LINES=500 run_script pr-gate.sh
assert_exit 1
assert_matches stdout '^pr-gate step FAILED: verify$'
assert_matches stdout '^\$ \./gradlew -q verify$'
assert_contains stdout "(441 earlier lines omitted)"
[ "$(wc -l <"$T_OUT/stdout")" -le 62 ] || _t_fail "report longer than 60 log lines plus two header lines"
assert_contains "$GITHUB_STEP_SUMMARY" "| mutation-gate | not run |"

# a module below threshold fails the mutation-gate step with the module, score and threshold
new_repo domain-low; fake_gradlew
run_script pr-gate.sh
assert_exit 1
assert_contains stdout "pr-gate step FAILED: mutation-gate"
assert_contains stdout "module :services:demo:domain-low: score 50% < threshold 80%"

# --skip-verify (CI: verify ran in the reusable verify.yml job) goes straight to the mutation steps
new_repo domain-ok; fake_gradlew
run_script pr-gate.sh --skip-verify --base-ref no-such-ref
assert_exit 0; assert_silent
[ ! -s "$CALLS" ] || _t_fail "--skip-verify must not call gradle: $(cat "$CALLS")"

# surviving mutant on a changed line: the step fails and names it
git_init
run_script pr-gate.sh --skip-verify --base-ref base --diff "$FIX/diff.patch" --body-file "$FIX/body-unjustified.md"
assert_exit 1; assert_contains stdout "pr-gate step FAILED: surviving-mutants"
assert_contains stdout "Price.kt:12 SURVIVED"

# dry run: DRY lines for every step, nothing executed
new_repo domain-low; fake_gradlew
run_script pr-gate.sh --dry-run
assert_exit 0
for s in verify mutation-gate surviving-mutants; do assert_contains stdout "DRY [$s]"; done
assert_contains stdout "./gradlew -q verify"
[ ! -s "$CALLS" ] || _t_fail "dry run executed: $(cat "$CALLS")"
HOOK_DRY_RUN=1 run_script pr-gate.sh
assert_exit 0; assert_contains stdout "DRY [verify]"

run_script pr-gate.sh --bogus
assert_exit 64; assert_contains stderr "Usage:"
test_done
