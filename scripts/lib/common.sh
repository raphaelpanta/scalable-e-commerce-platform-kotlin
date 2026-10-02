#!/usr/bin/env bash
# shellcheck disable=SC2034  # this library defines variables read by the scripts that source it
# Shared helpers for scripts/bootstrap-repo.sh and scripts/verify-repo.sh (sourced, never executed).
# Bash 3.2 compatible. Mutations go through `run` / `gh_write` / `gh_api_json` only, so --dry-run is
# trustworthy: reads execute, every mutation is printed with a DRY-RUN prefix and skipped.

DRY_RUN="${DRY_RUN:-0}"
VERBOSE="${VERBOSE:-0}"
GH_BIN="${GH_BIN:-gh}"

DEFAULT_REPO_NAME="scalable-e-commerce-platform-kotlin"
DEFAULT_BRANCH="main"
REPO_DESCRIPTION="Scalable e-commerce platform in Kotlin: microservices, hexagonal architecture and DDD on Spring Boot and Kafka."
REPO_TOPICS="ecommerce microservices kotlin spring-boot kafka hexagonal-architecture ddd"

STEP_NO=0

log() { printf '%s\n' "$*"; }
vlog() { if [ "$VERBOSE" = 1 ]; then printf '    %s\n' "$*"; fi; return 0; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# step MESSAGE: one progress line per pipeline step.
step() {
  STEP_NO=$((STEP_NO + 1))
  printf '[%02d] %s\n' "$STEP_NO" "$*"
}

# run CMD...: the single mutation wrapper for local commands.
run() {
  if [ "$DRY_RUN" = 1 ]; then
    printf 'DRY-RUN: %s\n' "$*"
    return 0
  fi
  vlog "+ $*"
  "$@"
}

# gh_read ARGS...: read-only gh call; always executed, also in dry-run.
gh_read() { "$GH_BIN" "$@"; }

# gh_write ARGS...: mutating gh call (e.g. `repo create`); printed and skipped in dry-run.
gh_write() {
  if [ "$DRY_RUN" = 1 ]; then
    printf 'DRY-RUN: gh %s\n' "$*"
    return 0
  fi
  vlog "+ gh $*"
  "$GH_BIN" "$@" >/dev/null
}

# gh_api_json METHOD PATH JSON: mutating `gh api` call with a JSON body on stdin (no string-built
# query values). On failure prints gh's message and returns non-zero.
gh_api_json() {
  local method="$1" path="$2" json="$3" out
  if [ "$DRY_RUN" = 1 ]; then
    printf 'DRY-RUN: gh api -X %s %s %s\n' "$method" "$path" "$json"
    return 0
  fi
  vlog "+ gh api -X $method $path"
  if ! out="$(printf '%s' "$json" | "$GH_BIN" api -X "$method" "$path" --input - 2>&1)"; then
    printf '%s\n' "$out" >&2
    return 1
  fi
}

# valid_slug_part VALUE: GitHub owner and repository names only (guards values placed in API paths).
valid_slug_part() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9._-]+$'; }

# remote_url OWNER NAME: remote used for `git remote add`; REPO_REMOTE_URL overrides it in tests.
remote_url() { printf '%s\n' "${REPO_REMOTE_URL:-https://github.com/$1/$2.git}"; }

# strip_sync_impact_report FILE: remove the leading `<!-- ... -->` block, but only when it precedes the
# first Markdown heading and contains "Sync Impact Report". Everything else stays byte-identical.
# Returns 0 whether or not the file changed; 1 if the file is missing.
strip_sync_impact_report() {
  local file="$1" tmp
  [ -f "$file" ] || return 1
  tmp="$(mktemp "${TMPDIR:-/tmp}/strip.XXXXXX")"
  awk '
    state == 2 { print; next }
    state == 1 {
      buf = buf $0 ORS
      if ($0 ~ /Sync Impact Report/) hit = 1
      if ($0 ~ /-->/) { if (!hit) printf "%s", buf; state = 0 }
      next
    }
    /^<!--/ {
      buf = $0 ORS; hit = ($0 ~ /Sync Impact Report/)
      if ($0 ~ /-->/) { if (!hit) printf "%s", buf } else state = 1
      next
    }
    { print; if ($0 ~ /^#/) state = 2 }
    END { if (state == 1) printf "%s", buf }
  ' "$file" >"$tmp"
  cat "$tmp" >"$file"
  rm -f "$tmp"
}
