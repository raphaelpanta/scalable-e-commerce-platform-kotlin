#!/usr/bin/env bash
# FR-018: .claude/hooks/run-gate.sh is the single entry point: file <path> | complete | pr.
. "$(dirname "$0")/lib.sh"
new_fixture
RG="$HOOKS_DIR/run-gate.sh"
[ -x "$RG" ] || { _t_fail "missing executable $RG"; test_done; }
rg() { (cd "$T_ROOT" && "$RG" "$@") >"$T_OUT/stdout" 2>"$T_OUT/stderr" </dev/null; RC=$?; }
PRICE_REL="services/demo/domain/src/main/kotlin/demo/Price.kt"

# file: builds the hook JSON (relative or absolute path) and forwards the exit code
rg file "$PRICE_REL";                 assert_exit 0; assert_silent
assert_invoked ":services:demo:domain:test --tests *.PriceSpec"
: >"$FIXTURE_LOG"; rg file "$T_ROOT/$PRICE_REL"; assert_exit 0; assert_invoked ":services:demo:domain:test"
FAKE_EXIT=1 rg file "$PRICE_REL";     assert_exit 2; assert_contains stderr "post-edit check FAILED: gradle"

# complete: the Stop gate with stop_hook_active=false (so a failure blocks with exit 2, not a systemMessage)
: >"$FIXTURE_LOG"; rg complete;       assert_exit 0; assert_silent
assert_invoked "gradlew -q --console=plain verify"
rm -f "$(marker_path)"
FAKE_EXIT=1 rg complete;              assert_exit 2; assert_contains stderr "Full quality check FAILED"
[ ! -s "$T_OUT/stdout" ] || _t_fail "complete must report on stderr when blocking"

# dry-run is forwarded
: >"$FIXTURE_LOG"; rm -f "$(marker_path)"
HOOK_DRY_RUN=1 rg complete;           assert_exit 0; assert_contains stdout "DRY [gradle:verify]"; assert_no_invocations
HOOK_DRY_RUN=1 rg file "$PRICE_REL";  assert_exit 0; assert_contains stdout "DRY ["; assert_no_invocations

# pr: calls .github/scripts/pr-gate.sh (stubbed here), forwards the exit code, --dry-run and bypass
mkdir -p "$T_ROOT/.github/scripts"
cat >"$T_ROOT/.github/scripts/pr-gate.sh" <<'SH'
#!/usr/bin/env bash
echo "pr-gate.sh $*" >>"$FIXTURE_LOG"
exit "${STUB_EXIT:-0}"
SH
chmod +x "$T_ROOT/.github/scripts/pr-gate.sh"
: >"$FIXTURE_LOG"; rg pr;             assert_exit 0; assert_invoked "pr-gate.sh"
STUB_EXIT=7 rg pr;                    assert_exit 7 "pr must forward the exit code"
: >"$FIXTURE_LOG"; HOOK_DRY_RUN=1 rg pr; assert_exit 0; assert_invoked "pr-gate.sh --dry-run"
: >"$FIXTURE_LOG"; HOOK_BYPASS=1 rg pr; assert_exit 0; assert_silent; assert_no_invocations
assert_contains "$T_ROOT/.claude/.cache/bypass.log" "gate=pr-local"
rm -f "$T_ROOT/.github/scripts/pr-gate.sh"
rg pr; [ "$RC" -ne 0 ] || _t_fail "pr without pr-gate.sh must fail loudly"

# bypass is honoured by file/complete through the hooks
: >"$FIXTURE_LOG"; FAKE_EXIT=1 HOOK_BYPASS=1 rg file "$PRICE_REL"; assert_exit 0; assert_silent; assert_no_invocations

# unknown or incomplete arguments: usage on stderr, exit 64
for args in "" "bogus" "file" "complete extra" "pr extra"; do
  : >"$FIXTURE_LOG"
  # shellcheck disable=SC2086
  rg $args
  assert_exit 64 "run-gate.sh $args"; assert_contains stderr "Usage:"; assert_no_invocations
done
test_done
