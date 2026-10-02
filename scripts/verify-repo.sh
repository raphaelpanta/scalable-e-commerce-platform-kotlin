#!/usr/bin/env bash
# Verify a published repository from the outside (feature 003, FR-012).
# Prints one OK or FAIL line per check, then VERIFIED (exit 0) or NOT VERIFIED (exit 1).
#
# Environment overrides (used by tests): GH_BIN (gh binary), REPO_ANON_URL (git URL read anonymously),
# ANON_API_BASE (unauthenticated API base, default https://api.github.com).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/lib/common.sh"

REPO=""
ADMIN_BYPASS=0
REQUIRE_CHECKS=""
FAILURES=0
CHECKS=0

usage() {
  cat <<USAGE
Usage: scripts/verify-repo.sh [--repo owner/name] [--admin-bypass] [--require-check CTX] [--verbose]

Checks, from the outside, that the repository is public, matches the local HEAD, contains nothing that
must stay local, and has the expected security and collaboration settings.
  --repo owner/name     Repository to verify (default: derived from the origin remote, else the gh user).
  --admin-bypass        Expect enforce_admins to be disabled (as set by bootstrap --admin-bypass).
  --require-check CTX   Also require status check CTX on the protected branch (repeatable).
  --verbose             Show details for passing checks.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --repo) [ "$#" -ge 2 ] || die "--repo needs a value"; REPO="$2"; shift ;;
    --admin-bypass) ADMIN_BYPASS=1 ;;
    --require-check) [ "$#" -ge 2 ] || die "--require-check needs a value"
      REQUIRE_CHECKS="${REQUIRE_CHECKS}${REQUIRE_CHECKS:+
}$2"; shift ;;
    --verbose) VERBOSE=1; export VERBOSE ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done

cd "$ROOT" || exit 1

if [ -z "$REPO" ]; then
  url="$(git remote get-url origin 2>/dev/null || true)"
  case "$url" in
    *github.com[:/]*) REPO="$(printf '%s' "$url" | sed -E 's#.*github\.com[:/]##; s#\.git$##')" ;;
  esac
fi
if [ -z "$REPO" ]; then
  login="$(gh_read api user --jq .login 2>/dev/null || true)"
  [ -n "$login" ] || die "cannot determine the repository: pass --repo owner/name"
  REPO="$login/$DEFAULT_REPO_NAME"
fi
printf '%s' "$REPO" | grep -Eq '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$' || die "invalid --repo: $REPO"

ANON_URL="${REPO_ANON_URL:-https://github.com/$REPO.git}"
ANON_API="${ANON_API_BASE:-https://api.github.com}"
LOCAL_BRANCH="$(git symbolic-ref -q --short HEAD 2>/dev/null || echo "$DEFAULT_BRANCH")"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/verify-repo.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# check NAME STATUS [DETAIL]: STATUS is 0 for pass.
check() {
  local name="$1" status="$2" detail="${3:-}"
  CHECKS=$((CHECKS + 1))
  if [ "$status" = 0 ]; then
    printf 'OK: %s\n' "$name"
    [ -z "$detail" ] || vlog "$detail"
  else
    FAILURES=$((FAILURES + 1))
    printf 'FAIL: %s%s\n' "$name" "${detail:+ ($detail)}"
  fi
}

# api_value PATH [JQ]: authenticated read; prints the value, or nothing when the call fails.
api_value() {
  if [ -n "${2:-}" ]; then
    gh_read api "$1" --jq "$2" 2>/dev/null || true
  else
    gh_read api "$1" 2>/dev/null || true
  fi
}

anon_git() { GIT_TERMINAL_PROMPT=0 git -c credential.helper= "$@"; }

# ---------------------------------------------------------------------------------------------------
# Publication (US1)
# ---------------------------------------------------------------------------------------------------
verify_publication() {
  local body rc
  body="$(curl -fsS --max-time 30 "$ANON_API/repos/$REPO" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" = 0 ] && printf '%s' "$body" | grep -Eq '"private"[[:space:]]*:[[:space:]]*false'; then
    check "public visibility (anonymous API)" 0
  else
    check "public visibility (anonymous API)" 1 "the unauthenticated API did not report a public repository; if it was created private run: gh repo edit $REPO --visibility public --accept-visibility-change-consequences"
  fi

  local refs
  if refs="$(anon_git ls-remote --symref "$ANON_URL" HEAD 2>&1)"; then
    check "anonymous git access" 0
  else
    check "anonymous git access" 1 "git ls-remote failed without credentials"
    refs=""
  fi

  local remote_branch
  remote_branch="$(printf '%s\n' "$refs" | sed -n 's#^ref: refs/heads/\([^[:space:]]*\)[[:space:]]*HEAD$#\1#p' | head -n 1)"
  if [ -n "$remote_branch" ] && [ "$remote_branch" = "$LOCAL_BRANCH" ]; then
    check "default branch matches local" 0
  else
    check "default branch matches local" 1 "remote '${remote_branch:-unknown}', local '$LOCAL_BRANCH'"
  fi

  CLONE="$TMP/clone"
  if anon_git clone -q "$ANON_URL" "$CLONE" >/dev/null 2>&1; then
    local remote_tree local_tree
    remote_tree="$(git -C "$CLONE" rev-parse 'HEAD^{tree}' 2>/dev/null || echo none)"
    local_tree="$(git rev-parse 'HEAD^{tree}' 2>/dev/null || echo unknown)"
    if [ "$remote_tree" = "$local_tree" ]; then
      check "anonymous clone tree equals local HEAD tree" 0 "$local_tree"
    else
      check "anonymous clone tree equals local HEAD tree" 1 "clone $remote_tree, local $local_tree"
    fi
  else
    CLONE=""
    check "anonymous clone tree equals local HEAD tree" 1 "anonymous clone failed"
  fi

  local desc topics
  desc="$(api_value "repos/$REPO" .description)"
  topics="$(api_value "repos/$REPO" '.topics | length')"
  if [ -n "$desc" ] && [ "$desc" != "null" ] && [ "${topics:-0}" -gt 0 ] 2>/dev/null; then
    check "description and topics present" 0
  else
    check "description and topics present" 1 "description='${desc:-}', topics=${topics:-0}"
  fi
}

# ---------------------------------------------------------------------------------------------------
# Cleanliness (US2)
# ---------------------------------------------------------------------------------------------------
verify_cleanliness() {
  local ignored forbidden="" p
  if [ -n "${CLONE:-}" ] && [ -d "$CLONE/.git" ]; then
    ignored="$(git -C "$CLONE" ls-files -ci --exclude-standard)"
    if [ -z "$ignored" ]; then
      check "no ignored paths in clone" 0
    else
      check "no ignored paths in clone" 1 "$(printf '%s' "$ignored" | head -n 3 | tr '\n' ' ')"
    fi

    for p in .claude/.cache .claude/settings.local.json .tokensave .rtk; do
      if [ -n "$(git -C "$CLONE" ls-files -- "$p")" ]; then forbidden="$forbidden $p"; fi
    done
    if git -C "$CLONE" grep -q 'Sync Impact Report' -- .specify/memory/constitution.md 2>/dev/null; then
      forbidden="$forbidden constitution:Sync-Impact-Report"
    fi
    if [ -z "$forbidden" ]; then
      check "no local-only paths or review scratch in clone" 0
    else
      check "no local-only paths or review scratch in clone" 1 "found:$forbidden"
    fi
  else
    check "no ignored paths in clone" 1 "no clone available"
    check "no local-only paths or review scratch in clone" 1 "no clone available"
  fi

  local ss pp alerts
  ss="$(api_value "repos/$REPO" .security_and_analysis.secret_scanning.status)"
  pp="$(api_value "repos/$REPO" .security_and_analysis.secret_scanning_push_protection.status)"
  [ "$ss" = enabled ] && check "secret scanning enabled" 0 || check "secret scanning enabled" 1 "status '${ss:-unknown}'"
  [ "$pp" = enabled ] && check "push protection enabled" 0 || check "push protection enabled" 1 "status '${pp:-unknown}'"

  alerts="$(api_value "repos/$REPO/secret-scanning/alerts?state=open" length)"
  if [ "$alerts" = 0 ]; then
    check "no open secret-scanning alerts" 0
  else
    check "no open secret-scanning alerts" 1 "open alerts: ${alerts:-unknown}"
  fi
}

# ---------------------------------------------------------------------------------------------------
# Collaboration (US3)
# ---------------------------------------------------------------------------------------------------
verify_collaboration() {
  local lic prot expected_admin=true expected sq pvr pct ctxs
  lic="$(api_value "repos/$REPO" .license.spdx_id)"
  [ "$lic" = MIT ] && check "licence is MIT" 0 || check "licence is MIT" 1 "reported '${lic:-none}'"

  [ "$ADMIN_BYPASS" = 0 ] || expected_admin=false
  expected="1,true,true,true,false,false,$expected_admin"
  prot="$(api_value "repos/$REPO/branches/$LOCAL_BRANCH/protection" \
    '[.required_pull_request_reviews.required_approving_review_count, .required_pull_request_reviews.dismiss_stale_reviews, .required_conversation_resolution.enabled, .required_linear_history.enabled, .allow_force_pushes.enabled, .allow_deletions.enabled, .enforce_admins.enabled] | map(tostring) | join(",")')"
  if [ "$prot" = "$expected" ]; then
    check "branch protection matches policy" 0
  else
    check "branch protection matches policy" 1 "expected (approvals,dismiss_stale,conversations,linear,force_push,deletions,enforce_admins) $expected, got '${prot:-unprotected}'"
  fi

  sq="$(api_value "repos/$REPO" '[.allow_squash_merge, .allow_merge_commit, .allow_rebase_merge, .delete_branch_on_merge] | map(tostring) | join(",")')"
  if [ "$sq" = "true,false,false,true" ]; then
    check "squash-only merge settings" 0
  else
    check "squash-only merge settings" 1 "expected squash,merge,rebase,delete_branch = true,false,false,true; got '${sq:-unknown}'"
  fi

  pvr="$(api_value "repos/$REPO/private-vulnerability-reporting" .enabled)"
  [ "$pvr" = true ] && check "private vulnerability reporting enabled" 0 || check "private vulnerability reporting enabled" 1 "reported '${pvr:-unknown}'"

  pct="$(api_value "repos/$REPO/community/profile" .health_percentage)"
  [ "$pct" = 100 ] && check "community profile is 100%" 0 || check "community profile is 100%" 1 "health_percentage ${pct:-unknown}"

  if [ -n "$REQUIRE_CHECKS" ]; then
    local ctx missing=""
    ctxs="$(api_value "repos/$REPO/branches/$LOCAL_BRANCH/protection" '.required_status_checks.contexts // [] | join(",")')"
    while IFS= read -r ctx; do
      case ",$ctxs," in *",$ctx,"*) ;; *) missing="$missing $ctx" ;; esac
    done <<EOT
$REQUIRE_CHECKS
EOT
    [ -z "$missing" ] && check "required status checks" 0 "$ctxs" || check "required status checks" 1 "missing:$missing (present: ${ctxs:-none})"
  fi
}

verify_publication
verify_cleanliness
verify_collaboration

if [ "$FAILURES" = 0 ]; then
  printf 'VERIFIED: %s (%s checks)\n' "$REPO" "$CHECKS"
else
  printf 'NOT VERIFIED: %s of %s checks failed for %s\n' "$FAILURES" "$CHECKS" "$REPO"
  exit 1
fi
