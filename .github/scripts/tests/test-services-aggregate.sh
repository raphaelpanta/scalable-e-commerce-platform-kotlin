#!/usr/bin/env bash
# shellcheck disable=SC1091,SC2016,SC2034  # sourced helper, literal stub text, T_TMPS is read by lib.sh
# T148 (feature 004, user story 9): .github/scripts/services-aggregate.sh against a stubbed `gh`. The stub answers the
# two API calls with canned, already-filtered output (changed files, check runs as name<TAB>status<TAB>conclusion), so
# the cases are: not triggered, triggered and green, one failure, never reported, still running, a fork. Also static
# checks of .github/workflows/required-checks.yml (pull_request only, read-only, pinned, job named services-aggregate).
. "$(dirname "$0")/lib.sh"
W="$REPO_ROOT/.github/workflows/required-checks.yml"
T_OUT="$(mktemp -d "${TMPDIR:-/tmp}/aggtest.XXXXXX")"; T_TMPS="$T_OUT"
STUB="$T_OUT/gh"
{
  echo '#!/usr/bin/env bash'
  echo 'case "$*" in'
  echo '  *"/pulls/"*"/files"*) cat "$STUB_DIR/files.txt" ;;'
  echo '  *"/check-runs"*) cat "$STUB_DIR/runs.tsv" ;;'
  echo '  *) echo "unexpected gh call: $*" >&2; exit 2 ;;'
  echo 'esac'
} >"$STUB"
chmod +x "$STUB"
export STUB_DIR="$T_OUT" GH_BIN="$STUB" GITHUB_REPOSITORY=acme/shop PR_NUMBER=7 HEAD_SHA=abc123
export AGG_INTERVAL=0 AGG_APPEAR_TIMEOUT=0 AGG_COMPLETE_TIMEOUT=0
unset FORK GITHUB_STEP_SUMMARY

# run_agg FILES RUNS: FILES one per line, RUNS tab separated lines (printf %b escapes)
run_agg() {
  printf '%b' "$1" >"$T_OUT/files.txt"
  printf '%b' "$2" >"$T_OUT/runs.tsv"
  run_script services-aggregate.sh
}
tab='\t'

# not triggered: a docs-only change, no service check exists
run_agg 'docs/ci-cd.md\n' 'pr-gate'"$tab"'completed'"$tab"'success\n'
assert_exit 0 "docs only"
assert_contains stdout "nothing to wait for"

# triggered and green: cart changed, cart is green, catalog (not triggered) is not required
run_agg 'services/cart/domain/Foo.kt\n' 'service-ci / cart'"$tab"'completed'"$tab"'success\n'
assert_exit 0 "cart green"
assert_contains stdout '`service-ci / cart` | success'

# the storefront reports `service-ci / storefront`, and the browser acceptance suite makes platform expected too
run_agg 'frontend/src/ui/App.tsx\n' 'service-ci / storefront'"$tab"'completed'"$tab"'success\nplatform'"$tab"'completed'"$tab"'success\n'
assert_exit 0 "storefront and platform green"
assert_contains stdout '`service-ci / storefront` | success'
run_agg 'frontend/src/ui/App.tsx\n' 'platform'"$tab"'completed'"$tab"'success\n'
assert_fails "storefront check never reported"

# platform and a service, both green; neutral and skipped pass
run_agg 'services/cart/domain/Foo.kt\nplatform/compose/docker-compose.yml\n' \
  'service-ci / cart'"$tab"'completed'"$tab"'neutral\nplatform'"$tab"'completed'"$tab"'skipped\n'
assert_exit 0 "cart and platform"

# a failure fails at once, even while another check is still running
run_agg 'libs/platform-core/src/Foo.kt\n' \
  'service-ci / cart'"$tab"'completed'"$tab"'failure\nservice-ci / order'"$tab"'in_progress'"$tab"'-\n'
assert_fails "failed check"
assert_contains stdout '`service-ci / cart` | failure'

# an expected check that never appears fails (APPEAR_TIMEOUT is 0 here)
run_agg 'services/payment/domain/Foo.kt\n' 'pr-gate'"$tab"'completed'"$tab"'success\n'
assert_fails "never reported"
assert_contains stdout 'missing'

# a check that exists although its workflow was not predicted is watched too
run_agg 'docs/x.md\n' 'platform'"$tab"'completed'"$tab"'failure\n'
assert_fails "unexpected failing platform"

# still running at the deadline fails
run_agg 'services/cart/domain/Foo.kt\n' 'service-ci / cart'"$tab"'in_progress'"$tab"'-\n'
assert_fails "still running"
assert_contains stdout 'still running'

# a generous timeout does not change a green result
AGG_APPEAR_TIMEOUT=600 AGG_COMPLETE_TIMEOUT=600 run_agg 'services/cart/domain/Foo.kt\n' \
  'service-ci / cart'"$tab"'completed'"$tab"'success\n'
assert_exit 0 "reported within the timeout"

# a fork pull request fails without calling the API
FORK=true run_agg 'docs/x.md\n' ''
assert_fails "fork"
unset FORK

# --- the workflow
[ -f "$W" ] || { _t_fail "missing $W"; test_done; }
grep -q '^  pull_request:' "$W" || _t_fail "required-checks.yml must trigger on pull_request"
grep -v '^[[:space:]]*#' "$W" | grep -q 'pull_request_target' && _t_fail "pull_request_target must never be used"
grep -q '^  services-aggregate:' "$W" || _t_fail "required-checks.yml needs the job services-aggregate"
grep -q '^    name: services-aggregate$' "$W" || _t_fail "the job must be named services-aggregate"
grep -Eq '^[[:space:]]*[a-z-]+:[[:space:]]*write[[:space:]]*$' "$W" && _t_fail "no write permission allowed"
grep -hE '^[[:space:]]*(-[[:space:]]+)?uses:' "$W" | grep -vqE '@[0-9a-f]{40} # v[0-9]+\.[0-9]+\.[0-9]+$' &&
  _t_fail "every uses: needs a full SHA and a trailing # vX.Y.Z comment"
grep -q 'persist-credentials: false' "$W" || _t_fail "checkout must not persist credentials"
grep -v '^[[:space:]]*#' "$W" | grep -q 'secrets\.' && _t_fail "required-checks.yml must not use secrets"
test_done
