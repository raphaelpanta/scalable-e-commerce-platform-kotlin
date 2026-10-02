#!/usr/bin/env bash
# FR-019 / FR-016: the pull request template carries the "Gate bypasses" section (filled with `none` or the
# lines of .claude/.cache/bypass.log) and the "## Mutant justifications" section (`path:line reason`), and the
# `pr-gate` job of .github/workflows/pr-gate.yml fails a pull request whose description lacks the bypass
# section. The check is the workflow's own inline step, extracted from the YAML and run here.
. "$(dirname "$0")/lib.sh"
TPL="$REPO_ROOT/.github/PULL_REQUEST_TEMPLATE.md"
WF="$REPO_ROOT/.github/workflows/pr-gate.yml"
[ -f "$TPL" ] || { _t_fail "missing $TPL"; test_done; }
[ -f "$WF" ] || { _t_fail "missing $WF"; test_done; }
grep -q '^## Gate bypasses$' "$TPL" || _t_fail "template lacks the '## Gate bypasses' section"
grep -q '\.claude/\.cache/bypass\.log' "$TPL" || _t_fail "template must point to .claude/.cache/bypass.log"
grep -q '^## Mutant justifications$' "$TPL" || _t_fail "template lacks the '## Mutant justifications' section"
grep -q 'path:line reason' "$TPL" || _t_fail "template must show the path:line reason format"

# the run: block of the "Gate bypasses section" step, de-indented
OUT="$(mktemp -d "${TMPDIR:-/tmp}/prtpl.XXXXXX")"
# shellcheck disable=SC2034  # T_TMPS is cleaned up by the shared trap in lib.sh
T_TMPS="$OUT"
awk '
  /- name: Gate bypasses section/ { s = 1; next }
  s && /^[ ]+run: \|/ { r = 1; match($0, /^ */); ind = RLENGTH + 2; next }
  r { if ($0 != "" && match($0, /^ */) && RLENGTH < ind) exit; print substr($0, ind + 1) }
' "$WF" >"$OUT/check.sh"
grep -q 'Gate bypasses' "$OUT/check.sh" || { _t_fail "no 'Gate bypasses section' step with a run block in pr-gate.yml"; test_done; }
check() { PR_BODY="$1" bash "$OUT/check.sh" >"$OUT/o" 2>&1; echo $?; }

[ "$(check "$(cat "$TPL")")" -ne 0 ] || _t_fail "the untouched template (empty bypass section) must fail"
[ "$(check '## Summary

Something.')" -ne 0 ] || _t_fail "a description without the section must fail"
grep -q '::error::' "$OUT/o" || _t_fail "the failure must be reported as a workflow error"
[ "$(check '')" -ne 0 ] || _t_fail "an empty description must fail"
filled="$(sed '/^## Gate bypasses$/a\
none' "$TPL")"
[ "$(check "$filled")" -eq 0 ] || _t_fail "the template with 'none' must pass: $(cat "$OUT/o")"
[ "$(check "$(printf '## Gate bypasses\r\n\r\n2026-10-02T10:00:00Z gate=stop user=dev file=- reason=flaky\r\n')")" -eq 0 ] ||
  _t_fail "pasted bypass lines (CRLF) must pass"
[ "$(check "$(printf '## Gate bypasses\n<!-- none -->\n## Mutant justifications\nnone\n')")" -ne 0 ] ||
  _t_fail "a section holding only a comment must fail"
test_done
