#!/usr/bin/env bash
# Unit test of .claude/hooks/lib/common.sh (T007): run_budgeted, bounded_tail, log_timing.
. "$(dirname "$0")/lib.sh"
new_fixture
LIB="$HOOKS_DIR/lib/common.sh"
[ -f "$LIB" ] || { _t_fail "missing $LIB"; test_done; }
# shellcheck source=../lib/common.sh
. "$LIB"
ROOT="$(hook_root)"
[ "$ROOT" = "$T_ROOT" ] || _t_fail "hook_root returned $ROOT, expected $T_ROOT"

# --- run_budgeted: kills a sleeping child (and its descendants) after the budget ---------------
cat >"$T_OUT/sleeper.sh" <<'SH'
#!/usr/bin/env bash
sleep 47 &
sleep 46
SH
chmod +x "$T_OUT/sleeper.sh"
t0="$(date +%s)"
run_budgeted 1 "$T_OUT/sleeper.sh" >/dev/null 2>&1; rc=$?
el=$(( $(date +%s) - t0 ))
[ "$rc" -eq 124 ] || _t_fail "run_budgeted returned $rc on timeout, expected 124"
assert_le "$el" 8 "run_budgeted must return shortly after the budget"
sleep 1
if pgrep -f 'sleep 4[67]$' >/dev/null 2>&1; then
  _t_fail "stray sleep process left behind"; pkill -f 'sleep 4[67]$' 2>/dev/null
fi

# --- run_budgeted: passes the exit code through and does not delay a fast command -------------------
t0="$(date +%s)"
run_budgeted 30 bash -c 'exit 3' >/dev/null 2>&1; rc=$?
[ "$rc" -eq 3 ] || _t_fail "run_budgeted returned $rc, expected the child's 3"
run_budgeted 30 true >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] || _t_fail "run_budgeted returned $rc for a passing command"
assert_le "$(( $(date +%s) - t0 ))" 3 "fast commands must not wait for the watchdog"
out="$(run_budgeted 30 bash -c 'echo hello; echo oops >&2' 2>&1)"
[ "$out" = "$(printf 'hello\noops')" ] || _t_fail "child output must pass through, got: $out"
run_budgeted 30 bash -c 'cat >/dev/null; echo done' >"$T_OUT/o" 2>&1 <<<"stdin data"
assert_contains "$T_OUT/o" "done"

# --- bounded_tail: never more than N lines, appends an omitted-lines marker ---------------------------
seq 1 500 >"$T_OUT/big.log"
bounded_tail "$T_OUT/big.log" 60 >"$T_OUT/tail.out"
assert_max_lines "$T_OUT/tail.out" 60
assert_contains "$T_OUT/tail.out" "earlier lines omitted)"
assert_contains "$T_OUT/tail.out" "500"
assert_not_contains "$T_OUT/tail.out" "(1 earlier"
seq 1 5 >"$T_OUT/small.log"
bounded_tail "$T_OUT/small.log" 60 >"$T_OUT/tail.out"
assert_not_contains "$T_OUT/tail.out" "omitted"
[ "$(wc -l <"$T_OUT/tail.out" | tr -d ' ')" -eq 5 ] || _t_fail "short logs must be returned whole"
: >"$T_OUT/empty.log"; bounded_tail "$T_OUT/empty.log" 10 >"$T_OUT/tail.out"
assert_max_lines "$T_OUT/tail.out" 0

# --- log_timing: appends one line to .claude/.cache/gate-timing.log ----------------------------------------
log_timing post-edit 7 pass
log_timing stop 12 fail
LOGF="$T_ROOT/.claude/.cache/gate-timing.log"
assert_file "$LOGF"
[ "$(wc -l <"$LOGF" | tr -d ' ')" -eq 2 ] || _t_fail "gate-timing.log should hold exactly two lines"
assert_matches "$LOGF" '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z gate=post-edit seconds=7 status=pass$'
assert_contains "$LOGF" "gate=stop seconds=12 status=fail"
test_done
