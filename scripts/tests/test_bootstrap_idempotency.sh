#!/usr/bin/env bash
# Idempotency test: the real path against the stub gh and a local bare remote converges and never duplicates.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
git_identity
command -v gitleaks >/dev/null 2>&1 || { echo "gitleaks must be installed to run this test (brew install gitleaks)" >&2; exit 1; }

OWNER=raphaelpanta
NAME=scalable-e-commerce-platform-kotlin

fresh() { # creates $P (project copy), $S (stub state), $R (bare remote)
  local t
  t="$(mk_tmp)"
  P="$t/proj"; S="$t/state"; R="$t/remote.git"
  new_project "$P"
  mkdir -p "$S/anon/repos/$OWNER"
  printf '{"private": false}\n' >"$S/anon/repos/$OWNER/$NAME"
  git init -q --bare -b main "$R"
  export GH_BIN="$P/scripts/tests/stubs/gh" GH_STUB_STATE="$S" GH_STUB_REMOTE_URL="$R" \
    REPO_REMOTE_URL="$R" REPO_ANON_URL="$R" ANON_API_BASE="file://$S/anon"
  unset GH_STUB_FAIL_ON GH_STUB_NO_ACTIONS
}
in_proj() { (cd "$P" && "$@"); }
count_calls() { grep -c "$1" "$S/calls.log" 2>/dev/null || true; }
commits() { git -C "$P" rev-list --count HEAD; }
snapshot() { { jq -S . "$S/repo.json"; } 2>/dev/null; }

test_case "first run publishes exactly one commit and one repo create"
fresh
mkdir -p "$P/.claude/.cache" "$P/.tokensave" "$P/node_modules/x"
echo cache >"$P/.claude/.cache/c.json"; echo '{}' >"$P/.claude/settings.local.json"
echo db >"$P/.tokensave/tokensave.db"; echo js >"$P/node_modules/x/i.js"
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --yes
assert_contains "$LAST_OUT" "VERIFIED" "verification summary"
assert_eq "1" "$(commits)" "commit count"
assert_eq "1" "$(count_calls '^WRITE gh repo create')" "repo create calls"
assert_eq "$(git -C "$P" rev-parse HEAD)" "$(git -C "$R" rev-parse main)" "remote main equals local HEAD"
assert_eq ".githooks" "$(git -C "$P" config core.hooksPath)" "hook path"
assert_eq "origin/main" "$(git -C "$P" rev-parse --abbrev-ref '@{upstream}')" "upstream tracking"
# Ignored or local-only material never reaches the commit.
assert_eq "" "$(git -C "$P" ls-files -ci --exclude-standard)" "no ignored path is tracked"
tracked="$(git -C "$P" ls-files)"
for ignored_path in .claude/.cache .claude/settings.local.json .tokensave node_modules; do
  assert_not_contains "$tracked" "$ignored_path" "local-only path committed"
done
assert_contains "$tracked" ".gitleaks.toml" "policy files are committed"

test_case "second run is a no-op that converges without drift"
before_snapshot="$(snapshot)"
before_payload="$(cat "$S/protection_payload.json")"
before_head="$(git -C "$R" rev-parse main)"
creates_before="$(count_calls '^WRITE gh repo create')"
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --yes
assert_eq "1" "$(commits)" "commit count after second run"
assert_eq "$creates_before" "$(count_calls '^WRITE gh repo create')" "no second repo create"
assert_eq "$before_head" "$(git -C "$R" rev-parse main)" "remote unchanged by second push"
assert_eq "$before_snapshot" "$(snapshot)" "settings unchanged by second run"
assert_eq "$before_payload" "$(cat "$S/protection_payload.json")" "protection payload unchanged"

test_case "recorded settings: security, topics, merge methods, reporting, actions"
r="$S/repo.json"
assert_eq "public" "$(jq -r .visibility "$r")" "visibility"
assert_eq "$(printf '%s\n' ecommerce microservices kotlin spring-boot kafka hexagonal-architecture ddd)" \
  "$(jq -r '.topics[]' "$r")" "topics"
assert_contains "$(jq -r .description "$r")" "Scalable e-commerce platform in Kotlin" "description"
assert_eq "enabled" "$(jq -r .security_and_analysis.secret_scanning.status "$r")" "secret scanning"
assert_eq "enabled" "$(jq -r .security_and_analysis.secret_scanning_push_protection.status "$r")" "push protection"
assert_eq "true" "$(jq -r .vulnerability_alerts "$r")" "dependabot alerts"
assert_eq "true" "$(jq -r .pvr "$r")" "private vulnerability reporting"
assert_eq "true,false,false,true" \
  "$(jq -r '[.allow_squash_merge,.allow_merge_commit,.allow_rebase_merge,.delete_branch_on_merge]|map(tostring)|join(",")' "$r")" \
  "squash-only merge settings"
assert_eq "PR_TITLE,PR_BODY" "$(jq -r '[.squash_merge_commit_title,.squash_merge_commit_message]|join(",")' "$r")" "squash message"
assert_eq "all_external_contributors" "$(jq -r .actions_fork.approval_policy "$r")" "fork PR approval"
assert_eq "read,false" "$(jq -r '[.actions_workflow.default_workflow_permissions,.actions_workflow.can_approve_pull_request_reviews]|map(tostring)|join(",")' "$r")" "workflow token"

test_case "protection payload matches the policy (no required checks by default)"
p="$S/protection_payload.json"
assert_eq "1,true" "$(jq -r '[.required_pull_request_reviews.required_approving_review_count,.required_pull_request_reviews.dismiss_stale_reviews]|map(tostring)|join(",")' "$p")" "reviews"
assert_eq "true,true,false,false,true" \
  "$(jq -r '[.required_conversation_resolution,.required_linear_history,.allow_force_pushes,.allow_deletions,.enforce_admins]|map(tostring)|join(",")' "$p")" "flags"
assert_eq "null" "$(jq -c .required_status_checks "$p")" "required_status_checks"
assert_eq "null" "$(jq -c .restrictions "$p")" "restrictions"

test_case "--require-check sets required status checks; --admin-bypass relaxes enforce_admins"
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --yes --require-check verify --admin-bypass
assert_eq '{"strict":true,"contexts":["verify"]}' "$(jq -c .required_status_checks "$p")" "required_status_checks"
assert_eq "false" "$(jq -r .enforce_admins "$p")" "enforce_admins with bypass"
assert_contains "$LAST_OUT" "admin bypass" "bypass logged"

test_case "interrupted after the commit but before repository creation: re-run recovers"
fresh
export GH_STUB_FAIL_ON="repo create"
assert_nonzero in_proj scripts/bootstrap-repo.sh --yes
unset GH_STUB_FAIL_ON
assert_eq "1" "$(commits)" "commit exists after interruption"
assert_file_missing "$S/repo.json" "repository was not created"
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --yes
assert_eq "1" "$(commits)" "still one commit after recovery"
assert_eq "$(git -C "$P" rev-parse HEAD)" "$(git -C "$R" rev-parse main)" "pushed after recovery"

test_case "interrupted after repository creation: re-run adds the remote and pushes"
fresh
git init -q -b main "$P" # pre-existing repository without commits
"$GH_BIN" repo create "$OWNER/$NAME" --public --description x >/dev/null
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --yes
assert_eq "1" "$(commits)" "single commit"
assert_eq "$(git -C "$P" rev-parse HEAD)" "$(git -C "$R" rev-parse main)" "pushed"

test_case "Actions hardening is best effort: unavailable endpoints warn instead of failing"
fresh
export GH_STUB_NO_ACTIONS=1
assert_exit_code 0 in_proj scripts/bootstrap-repo.sh --yes
assert_contains "$LAST_OUT" "WARN" "warning printed"
unset GH_STUB_NO_ACTIONS

test_case "interactive confirmation requires typing the repository name"
fresh
assert_nonzero bash -c "cd '$P' && echo wrong-name | scripts/bootstrap-repo.sh"
assert_file_missing "$P/.git" "nothing created without confirmation"
assert_exit_code 0 bash -c "cd '$P' && echo $NAME | scripts/bootstrap-repo.sh"

test_case "a seeded secret in the working tree aborts before the first commit"
fresh
printf 'token=ghp_%s\n' "$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 36)" >"$P/leaked.txt"
assert_nonzero in_proj scripts/bootstrap-repo.sh --yes
assert_contains "$LAST_OUT" "leaked.txt" "finding names the file"
assert_eq "0" "$(git -C "$P" rev-list --count HEAD 2>/dev/null || echo 0)" "no commit created"
assert_file_missing "$S/repo.json" "no repository created"

finish_tests
