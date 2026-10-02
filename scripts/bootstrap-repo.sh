#!/usr/bin/env bash
# Idempotent publication procedure for a public GitHub repository (feature 003).
#
# Each step reads the current state and acts only when it differs, so the script can be re-run after an
# interruption. Every mutation goes through run / gh_write / gh_api_json, so --dry-run prints each one
# prefixed with DRY-RUN and skips it (reads still execute).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/lib/common.sh"

OWNER=""
NAME="$DEFAULT_REPO_NAME"
BRANCH="$DEFAULT_BRANCH"
YES=0
ADMIN_BYPASS=0
REQUIRE_CHECKS=""   # newline-separated status-check contexts (Bash 3.2 friendly)

usage() {
  cat <<USAGE
Usage: scripts/bootstrap-repo.sh [options]

Creates (or converges) the public GitHub repository for this project, pushes the initial history and
applies the community, security and branch-protection settings.

Options:
  --dry-run               Print every mutating step prefixed with DRY-RUN; change nothing.
  --yes                   Skip the interactive confirmation (you must still have decided).
  --owner OWNER           GitHub account or organisation (default: the signed-in gh user).
  --name NAME             Repository name (default: $DEFAULT_REPO_NAME).
  --require-check CTX     Require status check CTX on $DEFAULT_BRANCH (repeatable), e.g. verify.
  --admin-bypass          Do not enforce branch protection on administrators (logged).
  --verbose               Show each executed command.
  --help                  Show this help.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --yes) YES=1 ;;
    --owner) [ "$#" -ge 2 ] || die "--owner needs a value"; OWNER="$2"; shift ;;
    --name) [ "$#" -ge 2 ] || die "--name needs a value"; NAME="$2"; shift ;;
    --require-check)
      [ "$#" -ge 2 ] || die "--require-check needs a value"
      printf '%s' "$2" | grep -Eq '^[A-Za-z0-9._/ -]+$' || die "invalid status check name: $2"
      REQUIRE_CHECKS="${REQUIRE_CHECKS}${REQUIRE_CHECKS:+
}$2"
      shift ;;
    --admin-bypass) ADMIN_BYPASS=1 ;;
    --verbose) VERBOSE=1; export VERBOSE ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done

cd "$ROOT"
valid_slug_part "$NAME" || die "invalid repository name: $NAME"
[ -z "$OWNER" ] || valid_slug_part "$OWNER" || die "invalid owner: $OWNER"

# --------------------------------------------------------------------------------------------------
# Step 1: read-only preflight. Any failure stops here, before any local or remote change.
# --------------------------------------------------------------------------------------------------
preflight() {
  step "Preflight (read-only)"
  local status_out login scopes_line

  status_out="$(gh_read auth status 2>&1)" ||
    die "gh is not signed in. Run: gh auth login (scopes: repo, workflow, read:org)"
  scopes_line="$(printf '%s\n' "$status_out" | grep -i 'token scopes' || true)"
  if [ -n "$scopes_line" ]; then
    case "$scopes_line" in
      *"'repo'"*) ;;
      *) die "the gh token lacks the 'repo' scope. Run: gh auth refresh -s repo -s workflow" ;;
    esac
  else
    vlog "token scopes not reported by gh (fine-grained token?); continuing"
  fi

  login="$(gh_read api user --jq .login 2>/dev/null)" || die "cannot resolve the signed-in user with gh api user"
  [ -n "$login" ] || die "gh api user returned no login"
  [ -n "$OWNER" ] || OWNER="$login"
  valid_slug_part "$OWNER" || die "invalid owner: $OWNER"

  GIT_NAME="$(git config user.name 2>/dev/null || true)"
  GIT_NAME="${GIT_NAME:-${GIT_AUTHOR_NAME:-}}"
  GIT_EMAIL="$(git config user.email 2>/dev/null || true)"
  GIT_EMAIL="${GIT_EMAIL:-${GIT_AUTHOR_EMAIL:-}}"
  [ -n "$GIT_NAME" ] && [ -n "$GIT_EMAIL" ] ||
    die "git identity is not configured. Run: git config --global user.name ... && git config --global user.email ..."

  # FR-003: the constitution must be published without its temporary review comment.
  if grep -q 'Sync Impact Report' .specify/memory/constitution.md 2>/dev/null; then
    die ".specify/memory/constitution.md still contains the Sync Impact Report comment; remove it before publishing (FR-003)"
  fi

  # Is the target name free, or already ours (re-run after an interruption)?
  local out size
  REPO_EXISTS=0
  if out="$(gh_read api "repos/$OWNER/$NAME" --jq .size 2>&1)"; then
    REPO_EXISTS=1
    size="$out"
    if ! repo_is_ours "$size"; then
      die "repository $OWNER/$NAME already exists and is not this project (it has content and no matching origin remote). Choose another --name or --owner"
    fi
  else
    case "$out" in
      *404* | *"Not Found"*) ;;
      *) die "cannot query $OWNER/$NAME: $out" ;;
    esac
  fi

  if command -v gitleaks >/dev/null 2>&1; then
    vlog "gitleaks $(gitleaks version 2>/dev/null || echo present)"
  elif [ "$DRY_RUN" = 1 ]; then
    log "gitleaks is not installed: would install it with Homebrew"
  fi
  log "Target: $OWNER/$NAME (public), default branch $BRANCH"
}

# An existing repository is "ours" when it is empty or the local origin remote points at it.
repo_is_ours() {
  local size="$1" url
  [ "${size:-0}" = 0 ] && return 0
  url="$(git remote get-url origin 2>/dev/null || true)"
  case "$url" in
    "$(remote_url "$OWNER" "$NAME")" | *"$OWNER/$NAME" | *"$OWNER/$NAME.git") return 0 ;;
  esac
  return 1
}

# Step 2: type the repository name to confirm; skipped in dry-run or with --yes.
confirm() {
  [ "$DRY_RUN" = 1 ] && return 0
  [ "$YES" = 1 ] && return 0
  log "This will create/update the PUBLIC repository $OWNER/$NAME and push the whole history."
  printf 'Type the repository name (%s) to confirm: ' "$NAME"
  local answer=""
  read -r answer || true
  [ "$answer" = "$NAME" ] || die "confirmation did not match; nothing was changed"
}

# Step 3: gitleaks (Homebrew install, skipped in dry-run).
ensure_gitleaks() {
  step "Ensure gitleaks"
  if command -v gitleaks >/dev/null 2>&1; then
    return 0
  fi
  if [ "$DRY_RUN" = 1 ]; then
    run brew install gitleaks
    return 0
  fi
  command -v brew >/dev/null 2>&1 || die "gitleaks is missing and Homebrew is not available. Install gitleaks (https://github.com/gitleaks/gitleaks) and retry"
  run brew install gitleaks >/dev/null
  command -v gitleaks >/dev/null 2>&1 || die "gitleaks installation failed; refusing to publish without a secret scan"
}

# --------------------------------------------------------------------------------------------------
# Local git steps: init, hook path, stage, unstage ignored, scan, single initial commit.
# --------------------------------------------------------------------------------------------------
local_git() {
  local have_git=0 have_head=0 current p
  [ -d .git ] && have_git=1
  if [ "$have_git" = 1 ] && git rev-parse -q --verify HEAD >/dev/null 2>&1; then have_head=1; fi

  step "Initialise git on $BRANCH"
  if [ "$have_git" = 0 ]; then
    run git init -q -b "$BRANCH"
  else
    current="$(git symbolic-ref -q --short HEAD 2>/dev/null || true)"
    if [ "$current" != "$BRANCH" ]; then
      [ "$have_head" = 0 ] || die "existing repository is on '$current', expected '$BRANCH'; not rewriting history"
      run git symbolic-ref HEAD "refs/heads/$BRANCH"
    fi
  fi

  step "Activate the pre-push hook (core.hooksPath .githooks)"
  [ -f .githooks/pre-push ] || die ".githooks/pre-push is missing"
  [ -x .githooks/pre-push ] || run chmod +x .githooks/pre-push
  run git config core.hooksPath .githooks

  if [ "$have_head" = 1 ]; then
    step "Initial commit already exists: skipping stage, scan and commit"
    return 0
  fi

  step "Stage content and drop ignored paths"
  run git add -A
  if [ "$have_git" = 1 ]; then
    git ls-files -ci --exclude-standard | while IFS= read -r p; do
      run git rm --cached -q -- "$p"
    done
  fi

  step "Scan staged content with gitleaks"
  if [ "$DRY_RUN" = 1 ]; then
    run gitleaks git --staged --redact --config .gitleaks.toml .
  else
    local out rc=0
    out="$(gitleaks git --staged --no-banner --no-color --verbose --redact --config .gitleaks.toml . 2>&1)" || rc=$?
    if [ "$rc" != 0 ]; then
      printf '%s\n' "$out" | grep -E '^(Finding|File|Line|RuleID):' >&2 || printf '%s\n' "$out" >&2
      die "gitleaks reported a finding in the staged content; nothing was committed. Remove the secret and re-run"
    fi
  fi

  step "Create the single initial commit"
  run git commit -q -m "chore: initial commit of the scalable e-commerce platform"
}

# --------------------------------------------------------------------------------------------------
# Remote steps.
# --------------------------------------------------------------------------------------------------
create_repo() {
  step "Create the public repository $OWNER/$NAME"
  if [ "$REPO_EXISTS" = 1 ]; then
    log "Repository already exists; skipping creation"
    if ! git remote get-url origin >/dev/null 2>&1; then
      run git remote add origin "$(remote_url "$OWNER" "$NAME")"
    fi
  else
    gh_write repo create "$OWNER/$NAME" --public --description "$REPO_DESCRIPTION" --source . --remote origin
  fi
}

push_main() {
  step "Push $BRANCH (no-op when up to date)"
  run git push -u origin "$BRANCH"
}

topics_json() {
  local t json=""
  for t in $REPO_TOPICS; do json="${json}${json:+,}\"$t\""; done
  printf '{"names":[%s]}' "$json"
}

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

repo_settings() {
  local repo="repos/$OWNER/$NAME"

  step "Apply description and topics"
  gh_api_json PATCH "$repo" "{\"description\":\"$(json_escape "$REPO_DESCRIPTION")\"}" || die "could not set the description"
  gh_api_json PUT "$repo/topics" "$(topics_json)" || die "could not set topics"

  step "Enable secret scanning, push protection and Dependabot alerts"
  gh_api_json PATCH "$repo" \
    '{"security_and_analysis":{"secret_scanning":{"status":"enabled"},"secret_scanning_push_protection":{"status":"enabled"}}}' ||
    die "could not enable secret scanning and push protection"
  gh_api_json PUT "$repo/vulnerability-alerts" '{}' || warn "could not enable Dependabot alerts; enable them in Settings > Code security"

  step "Enable private vulnerability reporting"
  gh_api_json PUT "$repo/private-vulnerability-reporting" '{}' || die "could not enable private vulnerability reporting"

  step "Squash-only merging"
  gh_api_json PATCH "$repo" \
    '{"allow_squash_merge":true,"allow_merge_commit":false,"allow_rebase_merge":false,"delete_branch_on_merge":true,"squash_merge_commit_title":"PR_TITLE","squash_merge_commit_message":"PR_BODY"}' ||
    die "could not apply merge settings"

  step "Actions hardening (best effort)"
  gh_api_json PUT "$repo/actions/permissions/fork-pr-contributor-approval" '{"approval_policy":"all_external_contributors"}' ||
    warn "fork-PR approval policy unavailable; set Settings > Actions > General > 'Require approval for all outside collaborators'"
  gh_api_json PUT "$repo/actions/permissions/workflow" '{"default_workflow_permissions":"read","can_approve_pull_request_reviews":false}' ||
    warn "default workflow token permissions unavailable; set Settings > Actions > General > Workflow permissions to read-only"
}

protection_json() {
  local checks="null" enforce=true ctx list=""
  if [ -n "$REQUIRE_CHECKS" ]; then
    while IFS= read -r ctx; do list="${list}${list:+,}\"$ctx\""; done <<EOT
$REQUIRE_CHECKS
EOT
    checks="{\"strict\":true,\"contexts\":[$list]}"
  fi
  [ "$ADMIN_BYPASS" = 0 ] || enforce=false
  printf '{"required_status_checks":%s,"enforce_admins":%s,"required_pull_request_reviews":{"required_approving_review_count":1,"dismiss_stale_reviews":true,"require_code_owner_reviews":false},"restrictions":null,"required_linear_history":true,"allow_force_pushes":false,"allow_deletions":false,"required_conversation_resolution":true}' \
    "$checks" "$enforce"
}

branch_protection() {
  step "Protect $BRANCH"
  if [ "$ADMIN_BYPASS" = 1 ]; then
    warn "admin bypass enabled: enforce_admins=false, the maintainer can push directly to $BRANCH"
  fi
  gh_api_json PUT "repos/$OWNER/$NAME/branches/$BRANCH/protection" "$(protection_json)" || die "could not protect $BRANCH"
}

ensure_public() {
  local private
  step "Check visibility"
  if [ "$DRY_RUN" = 1 ] && [ "$REPO_EXISTS" = 0 ]; then
    log "Repository does not exist yet; it will be created public"
    return 0
  fi
  private="$(gh_read api "repos/$OWNER/$NAME" --jq .private 2>/dev/null || echo unknown)"
  if [ "$private" = "true" ]; then
    if [ "$YES" = 1 ]; then
      gh_write repo edit "$OWNER/$NAME" --visibility public --accept-visibility-change-consequences
    else
      warn "the repository is private. Make it public with: gh repo edit $OWNER/$NAME --visibility public --accept-visibility-change-consequences"
    fi
  fi
}

verify_all() {
  step "Verify"
  local args ctx
  args=(--repo "$OWNER/$NAME")
  [ "$ADMIN_BYPASS" = 0 ] || args+=(--admin-bypass)
  if [ -n "$REQUIRE_CHECKS" ]; then
    while IFS= read -r ctx; do args+=(--require-check "$ctx"); done <<EOT
$REQUIRE_CHECKS
EOT
  fi
  if [ "$DRY_RUN" = 1 ]; then
    run scripts/verify-repo.sh "${args[@]}"
  else
    "$ROOT/scripts/verify-repo.sh" "${args[@]}"
  fi
}

# --------------------------------------------------------------------------------------------------
preflight
confirm
ensure_gitleaks
local_git
create_repo
push_main
repo_settings
branch_protection
ensure_public
verify_all

if [ "$DRY_RUN" = 1 ]; then
  log "Dry run complete: nothing was changed for $OWNER/$NAME"
else
  log "Done: https://github.com/$OWNER/$NAME"
fi
