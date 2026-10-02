#!/usr/bin/env bash
# FR-014 / SC-005 and runner safeguards: static checks of .github/workflows/pr-gate.yml and the reusable
# .github/workflows/verify.yml it calls (grep based, no yq needed; YAML syntax is checked when ruby exists).
. "$(dirname "$0")/lib.sh"
W="$REPO_ROOT/.github/workflows"
PR="$W/pr-gate.yml"; V="$W/verify.yml"
for f in "$PR" "$V"; do [ -f "$f" ] || { _t_fail "missing $f"; test_done; }; done
T_OUT="$(mktemp -d "${TMPDIR:-/tmp}/prgatewf.XXXXXX")"; T_TMPS="$T_OUT"
# code only: comment lines may name what is forbidden
nocomment() { grep -v '^[[:space:]]*#' "$@"; }
nocomment "$PR" >"$T_OUT/pr.code"; nocomment "$V" >"$T_OUT/v.code"

if command -v ruby >/dev/null 2>&1; then
  for f in "$PR" "$V"; do ruby -ryaml -e 'YAML.load_file(ARGV[0])' "$f" 2>/dev/null || _t_fail "invalid YAML: $f"; done
fi

# triggers: pull_request only for the gate; never pull_request_target; verify is reusable
grep -q '^  pull_request:' "$PR" || _t_fail "pr-gate.yml must trigger on pull_request"
grep -q 'pull_request_target' "$T_OUT/pr.code" "$T_OUT/v.code" && _t_fail "pull_request_target must never be used"
grep -q '^  workflow_call:' "$V" || _t_fail "verify.yml must be reusable (workflow_call)"
grep -q 'uses: \./\.github/workflows/verify\.yml' "$PR" || _t_fail "pr-gate.yml must call verify.yml"

# least privilege: one top-level permissions block, contents: read, nothing writable
for f in "$PR" "$V"; do
  awk '/^permissions:/{f=1;next} f&&/^[^ ]/{f=0} f&&NF{print}' "$f" | sed 's/^ *//' >"$T_OUT/perm"
  [ "$(cat "$T_OUT/perm")" = "contents: read" ] || _t_fail "$(basename "$f"): permissions must be exactly contents: read"
  grep -Eq '^[[:space:]]*[a-z-]+:[[:space:]]*write[[:space:]]*$' "$f" && _t_fail "$(basename "$f"): no write permission allowed"
done

# every action pinned to a full commit SHA (the local reusable workflow excepted)
grep -hE '^[[:space:]]*(-[[:space:]]+)?uses:' "$PR" "$V" | sed -E 's/.*uses:[[:space:]]*//; s/[[:space:]].*//' |
  while read -r u; do
    case "$u" in ./.github/workflows/*) continue ;; esac
    echo "$u" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$' || echo "$u"
  done >"$T_OUT/unpinned"
[ ! -s "$T_OUT/unpinned" ] || _t_fail "unpinned actions: $(tr '\n' ' ' <"$T_OUT/unpinned")"

# jobs: verify (reusable), mutation, hook-tests and the final aggregate pr-gate
for j in verify mutation hook-tests pr-gate; do grep -q "^  $j:" "$PR" || _t_fail "missing job $j"; done
awk '/^  pr-gate:/{f=1} f' "$PR" >"$T_OUT/agg"
grep -q 'name: pr-gate' "$T_OUT/agg" || _t_fail "the aggregate job must be named pr-gate"
grep -q 'needs: \[verify, mutation, hook-tests\]' "$T_OUT/agg" || _t_fail "pr-gate must need verify, mutation and hook-tests"
grep -q 'if: always()' "$T_OUT/agg" || _t_fail "pr-gate must run even when a needed job failed or was skipped"
grep -q 'Gate bypasses' "$T_OUT/agg" || _t_fail "pr-gate must check the Gate bypasses section of the description"

# runner and bounds: self-hosted labels, fork guard on every job that checks out code, timeouts, concurrency
grep -q 'runs-on: \[self-hosted, linux, ecommerce\]' "$PR" || _t_fail "self-hosted runner labels missing"
n_checkout="$(grep -c 'actions/checkout@' "$PR")"
n_guard="$(grep -c 'github.event.pull_request.head.repo.full_name == github.repository' "$PR")"
[ "$n_guard" -ge "$n_checkout" ] || _t_fail "every job that checks out code needs the fork guard ($n_guard < $n_checkout)"
grep -q 'timeout-minutes: 30' "$PR" || _t_fail "mutation job must have timeout-minutes: 30"
grep -q '^concurrency:' "$PR" && grep -q '^concurrency:' "$V" || _t_fail "both workflows need a concurrency group"
grep -A2 '^concurrency:' "$PR" | grep -q 'pr-gate-' || _t_fail "pr-gate concurrency group must differ from verify's"

# gate content: verify runs the repository gate (full Pitest included, no class filter); the scripts run
grep -q 'run: \./gradlew -q verify' "$V" || _t_fail "verify.yml must run ./gradlew -q verify"
grep -q 'mutation-reports: true' "$PR" || _t_fail "pr-gate must ask verify.yml for the Pitest reports"
grep -q '\.github/scripts/pr-gate\.sh --skip-verify' "$PR" || _t_fail "mutation job must run pr-gate.sh --skip-verify"
grep -q '\.claude/hooks/tests/run-all\.sh' "$PR" && grep -q '\.github/scripts/tests/run-all\.sh' "$PR" ||
  _t_fail "hook-tests job must run both test suites"
cat "$SCRIPTS_DIR"/*.sh "$SCRIPTS_DIR"/lib/*.sh | nocomment >"$T_OUT/scripts.code"
grep -l 'harness.mutation.classes' "$T_OUT/pr.code" "$T_OUT/v.code" "$T_OUT/scripts.code" 2>/dev/null | grep -q . &&
  _t_fail "the pull-request gate must run Pitest without a class filter"
grep -l 'HOOK_BYPASS' "$T_OUT/pr.code" "$T_OUT/v.code" "$T_OUT/scripts.code" 2>/dev/null | grep -q . && _t_fail "HOOK_BYPASS must not be referenced in CI"
# untrusted PR fields reach scripts only through env, never interpolated into a run: block
grep -E '^[[:space:]]*run:.*\$\{\{[^}]*github\.event\.pull_request\.(body|title|head\.ref)' "$PR" "$V" &&
  _t_fail "pull request text interpolated into a run step"
test_done
