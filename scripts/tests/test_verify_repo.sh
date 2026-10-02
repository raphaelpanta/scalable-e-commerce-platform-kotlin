#!/usr/bin/env bash
# verify-repo tests: publication, cleanliness and collaboration checks against the stub gh and a local bare remote.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
git_identity

OWNER=raphaelpanta
NAME=scalable-e-commerce-platform-kotlin
SLUG="$OWNER/$NAME"

stub_json() { printf '%s' "$3" | "$GH_BIN" api -X "$1" "repos/$SLUG$2" --input - >/dev/null; }
mutate() { jq "$1" "$S/repo.json" >"$S/repo.tmp" && mv "$S/repo.tmp" "$S/repo.json"; }
in_proj() { (cd "$P" && "$@"); }
verify() { in_proj scripts/verify-repo.sh --repo "$SLUG" "$@"; }

# world: a fully compliant published repository (local repo, bare remote, stub GitHub state).
world() {
  local t
  t="$(mk_tmp)"
  P="$t/proj"; S="$t/state"; R="$t/remote.git"
  new_project "$P"
  git init -q -b main "$P"
  git -C "$P" add -A && git -C "$P" commit -q -m "initial"
  git init -q --bare -b main "$R"
  git -C "$P" remote add origin "$R"
  git -C "$P" push -q origin main
  mkdir -p "$S/anon/repos/$OWNER"
  printf '{"private": false}\n' >"$S/anon/repos/$OWNER/$NAME"
  export GH_BIN="$P/scripts/tests/stubs/gh" GH_STUB_STATE="$S" REPO_ANON_URL="$R" ANON_API_BASE="file://$S/anon"
  "$GH_BIN" repo create "$SLUG" --public --description "Scalable e-commerce platform in Kotlin" >/dev/null
  stub_json PUT /topics '{"names":["ecommerce","kotlin"]}'
  stub_json PATCH "" '{"security_and_analysis":{"secret_scanning":{"status":"enabled"},"secret_scanning_push_protection":{"status":"enabled"}},"allow_squash_merge":true,"allow_merge_commit":false,"allow_rebase_merge":false,"delete_branch_on_merge":true}'
  stub_json PUT /private-vulnerability-reporting '{}'
  stub_json PUT /branches/main/protection '{"required_status_checks":null,"enforce_admins":true,"required_pull_request_reviews":{"required_approving_review_count":1,"dismiss_stale_reviews":true},"restrictions":null,"required_linear_history":true,"allow_force_pushes":false,"allow_deletions":false,"required_conversation_resolution":true}'
}

test_case "compliant repository is VERIFIED with one OK line per check"
world
assert_exit_code 0 verify
assert_contains "$LAST_OUT" "VERIFIED: " "summary"
assert_not_contains "$LAST_OUT" "NOT VERIFIED" "no failing summary"
assert_not_contains "$LAST_OUT" "FAIL" "no failing check"
n_ok="$(printf '%s\n' "$LAST_OUT" | grep -c '^OK:')"
[ "$n_ok" -ge 14 ] || _fail "expected at least 14 OK lines, got $n_ok"

test_case "publication: clone differing from local HEAD fails"
world
echo "extra" >"$P/extra.txt" && git -C "$P" add extra.txt && git -C "$P" commit -q -m "local only file"
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: anonymous clone tree equals local HEAD tree" "tree check"
assert_contains "$LAST_OUT" "NOT VERIFIED" "failing summary"

test_case "publication: private repository fails"
world
rm -f "$S/anon/repos/$OWNER/$NAME"
mutate '.private = true | .visibility = "private"'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: public visibility (anonymous API)" "visibility"
assert_contains "$LAST_OUT" "--visibility public --accept-visibility-change-consequences" "remedy"

test_case "publication: default branch must equal local branch"
world
git -C "$R" symbolic-ref HEAD refs/heads/other
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: default branch matches local" "default branch"

test_case "publication: description and topics must be present"
world
mutate '.description = "" | .topics = []'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: description and topics present" "metadata"

test_case "cleanliness: a clone containing an ignored path fails"
world
mkdir -p "$P/build" && echo x >"$P/build/out.class"
git -C "$P" add -f build/out.class && git -C "$P" commit -q -m "oops" && git -C "$P" push -q origin main
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: no ignored paths in clone" "ignored path"

test_case "cleanliness: a published .claude/settings.local.json fails"
world
mkdir -p "$P/.claude" && echo '{}' >"$P/.claude/settings.local.json"
git -C "$P" add -f .claude/settings.local.json && git -C "$P" commit -q -m "oops" && git -C "$P" push -q origin main
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: no local-only paths or review scratch in clone" "local-only path"

test_case "cleanliness: the Sync Impact Report text in the constitution fails"
world
mkdir -p "$P/.specify/memory"
printf '<!--\nSync Impact Report\n-->\n# C\n' >"$P/.specify/memory/constitution.md"
git -C "$P" add -A && git -C "$P" commit -q -m "oops" && git -C "$P" push -q origin main
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: no local-only paths or review scratch in clone" "review scratch"

test_case "cleanliness: secret scanning or push protection disabled fails"
world
mutate '.security_and_analysis.secret_scanning.status = "disabled"'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: secret scanning enabled" "secret scanning"
world
mutate '.security_and_analysis.secret_scanning_push_protection.status = "disabled"'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: push protection enabled" "push protection"

test_case "cleanliness: open secret-scanning alerts fail"
world
mutate '.open_alerts = 2'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: no open secret-scanning alerts" "alerts"

test_case "collaboration: licence other than MIT fails"
world
mutate '.license.spdx_id = "Apache-2.0"'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: licence is MIT" "licence"

test_case "collaboration: branch protection field mismatch fails"
world
stub_json PUT /branches/main/protection '{"required_status_checks":null,"enforce_admins":true,"required_pull_request_reviews":{"required_approving_review_count":2,"dismiss_stale_reviews":true},"restrictions":null,"required_linear_history":true,"allow_force_pushes":false,"allow_deletions":false,"required_conversation_resolution":true}'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: branch protection matches policy" "protection"
world
assert_exit_code 1 verify --admin-bypass # enforce_admins true is a mismatch when bypass is declared
assert_contains "$LAST_OUT" "FAIL: branch protection matches policy" "bypass mismatch"

test_case "collaboration: merge commit allowed fails"
world
mutate '.allow_merge_commit = true'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: squash-only merge settings" "merge settings"

test_case "collaboration: private vulnerability reporting disabled fails"
world
mutate '.pvr = false'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: private vulnerability reporting enabled" "pvr"

test_case "collaboration: community profile below 100 fails"
world
mutate '.community_pct = 71'
assert_nonzero verify
assert_contains "$LAST_OUT" "FAIL: community profile is 100%" "community"

test_case "required status check is reported when --require-check is given"
world
assert_nonzero verify --require-check verify
assert_contains "$LAST_OUT" "FAIL: required status checks" "missing required check"
stub_json PUT /branches/main/protection '{"required_status_checks":{"strict":true,"contexts":["verify"]},"enforce_admins":true,"required_pull_request_reviews":{"required_approving_review_count":1,"dismiss_stale_reviews":true},"restrictions":null,"required_linear_history":true,"allow_force_pushes":false,"allow_deletions":false,"required_conversation_resolution":true}'
assert_exit_code 0 verify --require-check verify
assert_contains "$LAST_OUT" "OK: required status checks" "required check present"

test_case "usage errors"
world
assert_nonzero verify --bogus
assert_exit_code 0 in_proj scripts/verify-repo.sh --help

finish_tests
