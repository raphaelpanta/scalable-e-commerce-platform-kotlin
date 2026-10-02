#!/usr/bin/env bash
# FR-016: a surviving (or uncovered) mutant on an added line fails unless the pull request description lists
# `path:line reason` under "## Mutant justifications"; unchanged lines and killed mutants are ignored.
. "$(dirname "$0")/lib.sh"
P="services/demo/domain-ok/$SRC_REL"

new_repo domain-ok domain-low
run_script surviving-mutants.sh --diff "$FIX/diff.patch" --body-file "$FIX/body-unjustified.md"
assert_exit 1
assert_contains stdout "$P:12 SURVIVED Replaced long multiplication with division"
assert_not_contains stdout ":13"            # killed on an added line: ignored
assert_not_contains stdout "domain-low"     # survivors on unchanged lines: ignored
assert_contains stdout "## Mutant justifications"

# justified in the body (file, PR_BODY or the event payload): passes silently
run_script surviving-mutants.sh --diff "$FIX/diff.patch" --body-file "$FIX/body-justified.md"
assert_exit 0; assert_silent
PR_BODY="$(cat "$FIX/body-justified.md")" run_script surviving-mutants.sh --diff "$FIX/diff.patch"
assert_exit 0 "PR_BODY"; assert_silent
tr '\n' '\036' <"$FIX/body-justified.md" | sed 's/\x1e/\r\n/g' >"$T_OUT/crlf.md"
jq -n --rawfile b "$T_OUT/crlf.md" '{pull_request:{body:$b}}' >"$T_OUT/event.json"
GITHUB_EVENT_PATH="$T_OUT/event.json" run_script surviving-mutants.sh --diff "$FIX/diff.patch"
assert_exit 0 "event payload with CRLF line ends"; assert_silent

# a justification needs a reason; one outside the section does not count
printf '## Mutant justifications\n\n%s:12\n' "$P" >"$T_OUT/noreason.md"
run_script surviving-mutants.sh --diff "$FIX/diff.patch" --body-file "$T_OUT/noreason.md"
assert_exit 1 "path:line without a reason"
printf '%s:12 because\n\n## Mutant justifications\n\n## Gate bypasses\n\nnone\n' "$P" >"$T_OUT/outside.md"
run_script surviving-mutants.sh --diff "$FIX/diff.patch" --body-file "$T_OUT/outside.md"
assert_exit 1 "justification outside the section"

# a comment-only change has no mutant on its lines; a survivor on an unchanged line passes
run_script surviving-mutants.sh --diff "$FIX/diff-comment.patch" --body-file "$FIX/body-unjustified.md"
assert_exit 0; assert_silent

# uncovered mutants (NO_COVERAGE) on an added line count as survivors
L="services/demo/domain-low/$SRC_REL"
sed "s#domain-ok#domain-low#g" "$FIX/diff.patch" >"$T_OUT/low.patch"
run_script surviving-mutants.sh --diff "$T_OUT/low.patch" --body-file "$FIX/body-unjustified.md"
assert_exit 1; assert_contains stdout "$L:12 NO_COVERAGE"

# the diff comes from git: <base>...HEAD added lines
new_repo domain-ok
sed -i.bak '12d' "$T_ROOT/services/demo/domain-ok/$SRC_REL"; rm -f "$T_ROOT/services/demo/domain-ok/$SRC_REL.bak"
git_init
cp "$FIX/Price.kt" "$T_ROOT/services/demo/domain-ok/$SRC_REL"; git_commit change
PR_BASE_SHA=base run_script surviving-mutants.sh --body-file "$FIX/body-unjustified.md"
assert_exit 1; assert_contains stdout "$P:12 SURVIVED"
run_script surviving-mutants.sh --base-ref base --body-file "$FIX/body-justified.md"
assert_exit 0; assert_silent

# without a base to diff against: a skip locally, a failure in CI
new_repo domain-ok
run_script surviving-mutants.sh --base-ref no-such-ref --body-file "$FIX/body-unjustified.md"
assert_exit 0; assert_silent
GITHUB_ACTIONS=true run_script surviving-mutants.sh --base-ref no-such-ref --body-file "$FIX/body-unjustified.md"
assert_exit 1; assert_contains stdout "cannot compute the changed lines"

# dry run
run_script surviving-mutants.sh --dry-run
assert_exit 0; assert_matches stdout '^DRY \[surviving-mutants\]: '
run_script surviving-mutants.sh --bogus
assert_exit 64
test_done
