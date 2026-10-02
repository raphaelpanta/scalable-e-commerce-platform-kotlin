#!/usr/bin/env bash
# Resilience test (T102): runs catalog with two replicas, browses the catalogue through the gateway every 200 ms, stops
# one replica, keeps browsing and counts the non-2xx responses after the stop. Passes when the count does not exceed the
# in-flight grace (default 2). Exit status 1 otherwise.
#
# Usage: scripts/resilience.sh [--no-build] [--keep] [--verbose] [-h]
#   --no-build  start without --build (images must already exist)
#   --keep      leave the stack running afterwards (default: tear down with `down -v`)
#   --verbose   print progress and Compose output
# Environment: RESILIENCE_GRACE (default 2, tolerated non-2xx after the stop), RESILIENCE_OBSERVE_SECONDS (default 20),
#              SMOKE_WAIT_SECONDS (default 300, health wait).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

BUILD=(--build)
KEEP=false
VERBOSE=0
CREATED_ENV=false
GRACE="${RESILIENCE_GRACE:-2}"
OBSERVE_SECONDS="${RESILIENCE_OBSERVE_SECONDS:-20}"
WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-300}"
PROFILES=(--profile core)
RESULTS=""
UP_LOG=""
LOOP_PID=""

usage() {
  sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=() ;;
    --keep) KEEP=true ;;
    --verbose) VERBOSE=1 ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown option: $arg" >&2; usage >&2; exit 2 ;;
  esac
done
export VERBOSE
UP_LOG="$(mktemp)"
RESULTS="$(mktemp)"

# shellcheck disable=SC2329  # invoked through the EXIT trap
cleanup() {
  local status=$?
  if [[ -n "$LOOP_PID" ]]; then
    kill "$LOOP_PID" >/dev/null 2>&1 || true
    wait "$LOOP_PID" >/dev/null 2>&1 || true
  fi
  if [[ "$KEEP" == "false" ]]; then
    log "tearing down"
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  else
    log "stack left running (--keep)"
  fi
  rm -f "$RESULTS" "$UP_LOG"
  if [[ "$CREATED_ENV" == "true" && "$KEEP" == "false" ]]; then
    rm -f "$COMPOSE_DIR/.env"
  fi
  exit "$status"
}
trap cleanup EXIT

detect_compose
ensure_env

log "starting core with catalog=2 ${BUILD[*]:-}"
if [[ "$VERBOSE" == "1" ]]; then
  compose up -d ${BUILD[@]+"${BUILD[@]}"} --scale catalog=2
elif ! compose up -d ${BUILD[@]+"${BUILD[@]}"} --scale catalog=2 >"$UP_LOG" 2>&1; then
  tail -n 40 "$UP_LOG" >&2
  echo "FAIL: compose up"
  exit 1
fi

if ! wait_healthy "$WAIT_SECONDS" "${APP_SERVICES[@]}"; then
  echo "FAIL: services not healthy after ${WAIT_SECONDS}s: ${WAIT_PENDING[*]}"
  exit 1
fi
catalog_ids=()
while read -r id; do
  [[ -n "$id" ]] && catalog_ids+=("$id")
done < <(compose ps -q catalog)
if [[ ${#catalog_ids[@]} -ne 2 ]]; then
  echo "FAIL: expected 2 catalog replicas, found ${#catalog_ids[@]}"
  exit 1
fi
echo "PASS: catalog running with 2 healthy replicas"

# Browse loop: one status code per line, every 200 ms, until killed.
(
  while true; do
    code="$(http_status "$GATEWAY_URL$CATALOG_PATH")"
    echo "$code" >>"$RESULTS"
    sleep 0.2
  done
) &
LOOP_PID=$!

sleep 3
before_stop_total="$(wc -l <"$RESULTS" | tr -d ' ')"
before_stop_bad="$(grep -vc '^2' "$RESULTS" || true)"
if [[ "$before_stop_total" -lt 5 || "$before_stop_bad" -gt 0 ]]; then
  echo "FAIL: browsing was not healthy before the stop ($before_stop_bad non-2xx of $before_stop_total)"
  exit 1
fi

victim="${catalog_ids[0]}"
log "stopping catalog replica $victim"
marker="$(wc -l <"$RESULTS" | tr -d ' ')"
docker stop "$victim" >/dev/null
sleep "$OBSERVE_SECONDS"

total="$(wc -l <"$RESULTS" | tr -d ' ')"
after=$((total - marker))
non2xx="$(tail -n +"$((marker + 1))" "$RESULTS" | grep -vc '^2' || true)"
log "requests after the stop: $after, non-2xx: $non2xx"

if [[ "$after" -lt 20 ]]; then
  echo "FAIL: only $after requests completed after the stop (expected at least 20)"
  exit 1
fi
if [[ "$non2xx" -le "$GRACE" ]]; then
  echo "PASS: $non2xx non-2xx of $after requests after stopping one catalog replica (grace $GRACE)"
  exit 0
fi
echo "FAIL: $non2xx non-2xx of $after requests after stopping one catalog replica (grace $GRACE)"
tail -n +"$((marker + 1))" "$RESULTS" | sort | uniq -c | sort -rn >&2
exit 1
