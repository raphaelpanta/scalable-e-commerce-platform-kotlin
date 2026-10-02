#!/usr/bin/env bash
# FR-015: per-module mutation score against the declared threshold (default 80) and the committed,
# non-decreasing baseline quality/mutation-baseline.json.
. "$(dirname "$0")/lib.sh"

# a module above the default threshold passes silently; a module without mutants passes
new_repo domain-ok domain-empty
run_script mutation-gate.sh
assert_exit 0; assert_silent

# a module below the threshold fails naming module, score and threshold
new_repo domain-ok domain-low
run_script mutation-gate.sh
assert_exit 1
assert_contains stdout "module :services:demo:domain-low: score 50% < threshold 80%"
assert_not_contains stdout "domain-ok"

# a declared threshold (pitest { mutationThreshold.set(N) }) replaces the default; never below 80
new_repo domain-ok
printf 'plugins {\n    id("kotlin-domain")\n}\n\npitest {\n    mutationThreshold.set(95)\n}\n' \
  >"$T_ROOT/services/demo/domain-ok/build.gradle.kts"
run_script mutation-gate.sh
assert_exit 1; assert_contains stdout "module :services:demo:domain-ok: score 91% < threshold 95%"
sed -i.bak 's/set(95)/set(50)/' "$T_ROOT/services/demo/domain-ok/build.gradle.kts"
cp "$FIX/pitest/domain-low.xml" "$T_ROOT/services/demo/domain-ok/build/reports/pitest/mutations.xml"
run_script mutation-gate.sh
assert_exit 1; assert_contains stdout "score 50% < threshold 80%"

# below the committed baseline fails even above 80
new_repo domain-ok
cp "$FIX/baseline.json" "$T_ROOT/quality/mutation-baseline.json"
run_script mutation-gate.sh
assert_exit 1
assert_contains stdout "module :services:demo:domain-ok: score 91% < baseline 95% (quality/mutation-baseline.json)"
printf '{ ":services:demo:domain-ok": 91, ":services:demo:gone": 99 }\n' >"$T_ROOT/quality/mutation-baseline.json"
run_script mutation-gate.sh
assert_exit 0 "equal to the baseline passes; a baseline entry without a report is ignored"; assert_silent

# the baseline may only increase relative to the base branch copy
new_repo domain-ok
printf '{ ":services:demo:domain-ok": 85 }\n' >"$T_ROOT/quality/mutation-baseline.json"
git_init
printf '{ ":services:demo:domain-ok": 80 }\n' >"$T_ROOT/quality/mutation-baseline.json"; git_commit lower
run_script mutation-gate.sh --base-ref base
assert_exit 1; assert_contains stdout "baseline :services:demo:domain-ok: 80 lowered from 85 on the base branch"
printf '{}\n' >"$T_ROOT/quality/mutation-baseline.json"; git_commit drop
run_script mutation-gate.sh --base-ref base
assert_exit 1; assert_contains stdout "baseline :services:demo:domain-ok: removed (was 85 on the base branch)"
printf '{ ":services:demo:domain-ok": 90 }\n' >"$T_ROOT/quality/mutation-baseline.json"; git_commit raise
PR_BASE_SHA=base run_script mutation-gate.sh
assert_exit 0 "raising the baseline passes (base from PR_BASE_SHA)"; assert_silent

# dry run: prints what it would check, reads nothing, exits 0
new_repo domain-low
run_script mutation-gate.sh --dry-run
assert_exit 0; assert_matches stdout '^DRY \[mutation-gate\]: '
HOOK_DRY_RUN=1 run_script mutation-gate.sh
assert_exit 0; assert_matches stdout '^DRY \[mutation-gate\]: '
run_script mutation-gate.sh --bogus
assert_exit 64
test_done
