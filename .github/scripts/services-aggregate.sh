#!/usr/bin/env bash
# T148 (feature 004, user story 9): the path-neutral aggregate behind the required status check `services-aggregate`.
# The per-service workflows (`service-ci / <ctx>`) and `platform` are path-filtered: GitHub reports no check at all for
# a workflow whose `paths:` filter skipped it, and a required check that is never reported blocks the merge. This
# script runs on EVERY pull request (.github/workflows/required-checks.yml) and answers one question: did every
# service check that the change triggered succeed?
#
#   1. The changed files of the pull request (GitHub API) go through .github/scripts/path-filter-check.sh, which
#      lists the path-filtered workflows the change triggers. Each maps to a check name: <ctx>.yml to
#      `service-ci / <ctx>`, platform.yml to `platform`. Workflows that are not listed were "not triggered":
#      that counts as success.
#   2. The check runs of the head commit (`gh api repos/<repo>/commits/<sha>/check-runs`) are polled until every
#      expected check has been reported (AGG_APPEAR_TIMEOUT seconds) and has completed (AGG_COMPLETE_TIMEOUT).
#      A check that exists although its workflow was not predicted is watched as well.
#   3. success, neutral and skipped conclusions pass; failure, cancelled, timed_out, action_required, stale and
#      startup_failure fail at once. An expected check that never appears fails (re-run the workflow). A pull request
#      from a fork fails: the service pipelines never run on the self-hosted runner for forks (pr-gate does the same).
#
# Usage (environment): GH_TOKEN (read access to checks and pull requests), GITHUB_REPOSITORY, PR_NUMBER, HEAD_SHA,
#   FORK=true|false (default false); optional AGG_INTERVAL (20 s), AGG_APPEAR_TIMEOUT (600 s), AGG_COMPLETE_TIMEOUT
#   (2400 s), GH_BIN (default gh), GITHUB_STEP_SUMMARY. Exit status 0 when the aggregate is green, 1 otherwise.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GH="${GH_BIN:-gh}"
INTERVAL="${AGG_INTERVAL:-20}"
APPEAR_TIMEOUT="${AGG_APPEAR_TIMEOUT:-600}"
COMPLETE_TIMEOUT="${AGG_COMPLETE_TIMEOUT:-2400}"
SERVICES="gateway identity catalog cart order payment notification"

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${HEAD_SHA:?HEAD_SHA is required}"

summary() { [ -z "${GITHUB_STEP_SUMMARY:-}" ] || printf '%s\n' "$*" >>"$GITHUB_STEP_SUMMARY"; }

if [ "${FORK:-false}" = true ]; then
  echo "::error::services-aggregate: pull requests from forks are not built on the self-hosted runner; a maintainer pushes the branch to this repository."
  summary "### services-aggregate" "" "Fork pull request: the service pipelines do not run for it, so the aggregate fails."
  exit 1
fi

# --- 1. the checks the change triggers
files=""
if ! files="$("$GH" api --paginate "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER/files?per_page=100" \
  --jq '.[] | .filename, (.previous_filename // empty)')"; then
  echo "::error::services-aggregate: cannot list the files of pull request #$PR_NUMBER"
  exit 1
fi
workflows=""
if [ -n "$files" ]; then
  workflows="$(printf '%s\n' "$files" | "$HERE/path-filter-check.sh" -)" || {
    echo "::error::services-aggregate: path-filter-check.sh failed"
    exit 1
  }
fi
expected=""
for wf in $workflows; do
  ctx="${wf%.yml}"
  case " $SERVICES " in
    *" $ctx "*) expected="${expected}service-ci / $ctx"$'\n' ;;
    *) [ "$wf" != platform.yml ] || expected="${expected}platform"$'\n' ;;
  esac
done
echo "services-aggregate: expected checks: $(printf '%s' "$expected" | tr '\n' ',' | sed 's/,$//; s/,/, /g')"

# --- 2./3. poll the check runs of the head commit
is_watched() { # NAME: expected or a service/platform check that exists anyway
  printf '%s' "$expected" | grep -qxF -- "$1" && return 0
  case "$1" in
    platform) return 0 ;;
    "service-ci / "*) case " $SERVICES " in *" ${1#service-ci / } "*) return 0 ;; esac ;;
  esac
  return 1
}

start=$SECONDS
api_failures=0
while true; do
  if ! runs="$("$GH" api --paginate "repos/$GITHUB_REPOSITORY/commits/$HEAD_SHA/check-runs?filter=latest&per_page=100" \
    --jq '.check_runs[] | [.name, .status, (.conclusion // "-")] | @tsv')"; then
    api_failures=$((api_failures + 1))
    if [ "$api_failures" -ge 5 ]; then
      echo "::error::services-aggregate: the check-runs API failed $api_failures times in a row"
      exit 1
    fi
    sleep "$INTERVAL"
    continue
  fi
  api_failures=0

  elapsed=$((SECONDS - start))
  table=""
  pending=0
  failed=0
  watched="$(printf '%s\n' "$runs" | cut -f1 | while IFS= read -r n; do is_watched "$n" && printf '%s\n' "$n"; done)"
  watched="$(printf '%s\n%s\n' "$expected" "$watched" | sed '/^$/d' | sort -u)"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    line="$(printf '%s\n' "$runs" | awk -F'\t' -v n="$name" '$1 == n { print; exit }')"
    if [ -z "$line" ]; then
      if [ "$elapsed" -ge "$APPEAR_TIMEOUT" ]; then
        verdict="missing (never reported)"
        failed=1
      else
        verdict="waiting to be reported"
        pending=1
      fi
    else
      status="$(printf '%s' "$line" | cut -f2)"
      conclusion="$(printf '%s' "$line" | cut -f3)"
      if [ "$status" != completed ]; then
        verdict="$status"
        pending=1
      else
        case "$conclusion" in
          success | neutral | skipped) verdict="$conclusion" ;;
          *) verdict="$conclusion"; failed=1 ;;
        esac
      fi
    fi
    table="${table}| \`$name\` | $verdict |"$'\n'
  done <<EOF
$watched
EOF

  if [ "$failed" -eq 1 ] || [ "$pending" -eq 0 ] || [ "$elapsed" -ge "$COMPLETE_TIMEOUT" ]; then
    {
      echo "### services-aggregate"
      echo
      if [ -n "$table" ]; then
        echo "| check | result |"
        echo "| --- | --- |"
        printf '%s' "$table"
      else
        echo "No service or platform check was triggered by this change: nothing to wait for."
      fi
      echo
      echo "Workflows that were not triggered (path filter) count as success."
    } | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
    if [ "$failed" -eq 1 ]; then
      echo "::error::services-aggregate: a service check failed or was never reported (see the summary)."
      exit 1
    fi
    if [ "$pending" -eq 1 ]; then
      echo "::error::services-aggregate: checks still running after ${COMPLETE_TIMEOUT}s."
      exit 1
    fi
    exit 0
  fi
  sleep "$INTERVAL"
done
