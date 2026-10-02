#!/usr/bin/env bash
# Compose smoke test (T101): starts the core and observability profiles, waits for every service to be healthy
# (max 5 minutes), calls the catalogue through the gateway and checks that databases, Kafka and service ports are not
# published on the host. One PASS/FAIL line per check; exit status 1 when any check fails.
#
# Usage: scripts/smoke.sh [--no-build] [--keep] [--verbose] [-h]
#   --no-build  start without --build (images must already exist)
#   --keep      leave the stack running afterwards (default: tear down with `down -v`)
#   --verbose   print progress and Compose output
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

BUILD=(--build)
# The seven images share one Gradle cache mount; building them in parallel makes Gradle's lock files collide
# across containers, so images are built one at a time unless the caller raises the limit.
export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-1}"
KEEP=false
VERBOSE=0
CREATED_ENV=false
WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-300}"
PROFILES=(--profile core --profile observability)
FAILURES=0
UP_LOG=""

usage() {
  sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }

# shellcheck disable=SC2329  # invoked through the EXIT trap
cleanup() {
  local status=$?
  if [[ "$KEEP" == "false" ]]; then
    log "tearing down"
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  else
    log "stack left running (--keep)"
  fi
  rm -f "$UP_LOG"
  if [[ "$CREATED_ENV" == "true" && "$KEEP" == "false" ]]; then
    rm -f "$COMPOSE_DIR/.env"
  fi
  exit "$status"
}
trap cleanup EXIT

detect_compose
ensure_env

log "starting core + observability ${BUILD[*]:-}"
if [[ "$VERBOSE" == "1" ]]; then
  compose up -d ${BUILD[@]+"${BUILD[@]}"}
elif ! compose up -d ${BUILD[@]+"${BUILD[@]}"} >"$UP_LOG" 2>&1; then
  tail -n 40 "$UP_LOG" >&2
  fail "compose up"
  exit 1
fi
pass "compose up (core + observability)"

# --- every service healthy within 5 minutes
if wait_healthy "$WAIT_SECONDS" "${APP_SERVICES[@]}"; then
  pass "all services healthy (${APP_SERVICES[*]})"
else
  fail "services not healthy after ${WAIT_SECONDS}s: ${WAIT_PENDING[*]} (a state of 'none' means the image has no HEALTHCHECK; with Podman build with --format docker)"
  compose ps >&2 || true
  exit 1
fi

# --- catalogue through the gateway
status="$(http_status "$GATEWAY_URL$CATALOG_PATH")"
if [[ "$status" == "200" ]]; then
  pass "GET $CATALOG_PATH through the gateway returned 200"
else
  fail "GET $CATALOG_PATH through the gateway returned $status (expected 200)"
fi

# --- ports that must not be reachable on the host: PostgreSQL, Kafka and a service management port
for port in 5432 9092 8081; do
  rc=0
  curl -s -o /dev/null --connect-timeout 2 --max-time 3 "http://localhost:$port" || rc=$?
  # 7 = connection refused, 28 = timed out; anything else means something accepted the connection
  if [[ "$rc" == "7" || "$rc" == "28" ]]; then
    pass "localhost:$port is not reachable"
  else
    fail "localhost:$port accepted a connection (curl exit $rc)"
  fi
done

# --- the only published container ports are the gateway, Grafana and Mailpit
published=""
while read -r id; do
  [[ -n "$id" ]] || continue
  ports="$(docker inspect -f '{{range $p, $b := .NetworkSettings.Ports}}{{if $b}}{{$p}} {{end}}{{end}}' "$id" 2>/dev/null || true)"
  published+="$ports"
done < <(compose ps -q 2>/dev/null)
unexpected=""
for p in $published; do
  case "$p" in
    8080/tcp | 3000/tcp | 8025/tcp) ;;
    *) unexpected+="$p " ;;
  esac
done
if [[ -z "$unexpected" ]]; then
  pass "only gateway 8080, Grafana 3000 and Mailpit 8025 are published"
else
  fail "unexpected published ports: $unexpected"
fi

if [[ "$FAILURES" -gt 0 ]]; then
  echo "SMOKE FAILED: $FAILURES check(s)"
  exit 1
fi
echo "SMOKE PASSED"
