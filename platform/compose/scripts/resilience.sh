#!/usr/bin/env bash
# Resilience test (T102, extended by T153; FR-024, SC-008). Phase 1 runs catalog with two replicas, browses the catalogue
# through the gateway every 200 ms, stops one replica, keeps browsing and counts the non-2xx responses after the stop;
# it passes when the count does not exceed the in-flight grace (default 2). Phase 1b removes the stopped replica,
# scales catalog back to two (`--scale catalog=2`), waits until the new replica is healthy and proves that it receives
# traffic: the `http_server_requests_seconds_count` series of its own `/actuator/prometheus` (read from inside the
# network with `docker exec`, the management port is not published) must grow while the browse loop keeps running, and
# the scale-up itself must not cause more than the grace of non-2xx. Phase 2 repeats the stop against a POST-heavy
# service: identity with two replicas, sign-in with a wrong password (cheap and safe, expects 401: never a 5xx, never
# a 200) from fresh, unknown e-mail addresses so that no account is ever locked; the gateway does not retry POST
# requests (only idempotent reads), so the few requests of the DNS cache window after the stop that reach the stopped
# instance are tolerated up to RESILIENCE_POST_GRACE and reported, the steady state before the stop and the end of the
# run must be clean. Exit status 1 when any phase fails.
#
# Usage: scripts/resilience.sh [--no-build] [--keep] [--verbose] [-h]
#   --no-build  start without --build (images must already exist)
#   --keep      leave the stack running afterwards (default: tear down with `down -v`)
#   --verbose   print progress and Compose output
# Environment: RESILIENCE_GRACE (default 2, tolerated non-2xx after the stop and during the scale-up),
#              RESILIENCE_OBSERVE_SECONDS (default 20), RESILIENCE_PROOF_SECONDS (default 90, how long the new catalog
#              replica may take to receive its first request), RESILIENCE_POST_ATTEMPTS (default 8 sign-ins before and 8
#              after the stop; the identity service throttles a source after 20 failures), RESILIENCE_POST_PACE (seconds
#              between sign-ins, default 1), RESILIENCE_POST_GRACE (default 5, tolerated 5xx/connection errors after
#              the stop: the JVM caches DNS answers for 5 s, Dockerfile networkaddress.cache.ttl), SMOKE_WAIT_SECONDS
#              (default 300, health wait), COMPOSE_FILE (default: docker-compose.yml plus ../perf/compose.perf.yml,
#              which lifts the gateway's per-address rate limits so that the sign-ins reach identity; a stack already
#              running without the override has its gateway recreated).
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
PROOF_SECONDS="${RESILIENCE_PROOF_SECONDS:-90}"
POST_ATTEMPTS="${RESILIENCE_POST_ATTEMPTS:-8}"
POST_PACE="${RESILIENCE_POST_PACE:-1}"
POST_GRACE="${RESILIENCE_POST_GRACE:-5}"
PROFILES=(--profile core)
RESULTS=""
UP_LOG=""
LOOP_PID=""
POST_BEFORE=""
POST_AFTER=""

usage() {
  sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'
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
  rm -f "$RESULTS" "$UP_LOG" ${POST_BEFORE:+"$POST_BEFORE"} ${POST_AFTER:+"$POST_AFTER"}
  if [[ "$CREATED_ENV" == "true" && "$KEEP" == "false" ]]; then
    rm -f "$COMPOSE_DIR/.env"
  fi
  exit "$status"
}
trap cleanup EXIT

detect_compose
ensure_env

# The sign-in phase needs the gateway's auth tier (10 requests per minute and source address by default) out of the way.
if [[ -z "${COMPOSE_FILE:-}" && -f "$COMPOSE_DIR/../perf/compose.perf.yml" ]]; then
  export COMPOSE_FILE="docker-compose.yml:../perf/compose.perf.yml"
  log "using the rate-limit override platform/perf/compose.perf.yml"
fi

# replica_ids <service>: container ids of every replica, one per line.
replica_ids() {
  local id
  while read -r id; do
    [[ -n "$id" ]] && echo "$id"
  done < <(compose ps -q "$1" 2>/dev/null || true)
}

# request_count <container-id> <uri-prefix>: sum of the server-side http_server_requests_seconds_count series whose uri
# starts with the prefix, read from the replica's own Prometheus endpoint (management port 8081, not published; the JRE
# image has no curl, bash's /dev/tcp is enough). Prints 0 when the replica does not answer.
request_count() {
  docker exec "$1" bash -c \
    'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/prometheus HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3' 2>/dev/null |
    awk -v prefix="uri=\"$2" '/^http_server_requests_seconds_count[{]/ && index($0, prefix) > 0 { sum += $NF } END { printf "%d\n", sum }'
}

# scale <service> <replicas>: scales without touching anything else and without recreating healthy replicas.
scale() {
  if [[ "$VERBOSE" == "1" ]]; then
    compose up -d --no-recreate --no-deps --scale "$1=$2" "$1"
  elif ! compose up -d --no-recreate --no-deps --scale "$1=$2" "$1" >"$UP_LOG" 2>&1; then
    tail -n 40 "$UP_LOG" >&2
    echo "FAIL: compose up --scale $1=$2"
    exit 1
  fi
}

stop_browse_loop() {
  if [[ -n "$LOOP_PID" ]]; then
    kill "$LOOP_PID" >/dev/null 2>&1 || true
    wait "$LOOP_PID" >/dev/null 2>&1 || true
    LOOP_PID=""
  fi
}

# sign_in_status <n>: POST /sessions through the gateway with an unknown e-mail address and a wrong password.
sign_in_status() {
  curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 8 -X POST \
    -H 'Content-Type: application/json' -H "X-Correlation-Id: resilience-post-$$-$1" \
    --data "{\"email\":\"resilience-$$-$1@ecommerce.example\",\"password\":\"Wrong-Passw0rd!2026\"}" \
    "$GATEWAY_URL/api/v1/identity/sessions" || true
}

# post_burst <first-index> <count> <file>: <count> sign-ins, one status code per line appended to <file>.
post_burst() {
  local first="$1" count="$2" file="$3" i
  for ((i = 0; i < count; i++)); do
    printf '%s\n' "$(sign_in_status "$((first + i))")" >>"$file"
    sleep "$POST_PACE"
  done
}

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
done < <(replica_ids catalog)
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
survivor="${catalog_ids[1]}"
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
if [[ "$non2xx" -gt "$GRACE" ]]; then
  echo "FAIL: $non2xx non-2xx of $after requests after stopping one catalog replica (grace $GRACE)"
  tail -n +"$((marker + 1))" "$RESULTS" | sort | uniq -c | sort -rn >&2
  exit 1
fi
echo "PASS: $non2xx non-2xx of $after requests after stopping one catalog replica (grace $GRACE)"

# --- Phase 1b: scale back up and prove that the new replica receives traffic.
# The stopped container is removed first: `up --scale` would otherwise only restart it, and the point is a NEW replica
# (new address) that the gateway has to discover through DNS.
log "removing the stopped replica and scaling catalog back to 2"
docker rm -f "$victim" >/dev/null
scale_marker="$(wc -l <"$RESULTS" | tr -d ' ')"
scale catalog 2
if ! wait_healthy "$WAIT_SECONDS" catalog; then
  echo "FAIL: catalog not healthy after scaling back to 2 within ${WAIT_SECONDS}s: ${WAIT_PENDING[*]}"
  exit 1
fi
new_id=""
replica_count=0
while read -r id; do
  [[ -n "$id" ]] || continue
  replica_count=$((replica_count + 1))
  [[ "$id" == "$survivor" ]] || new_id="$id"
done < <(replica_ids catalog)
if [[ "$replica_count" -ne 2 || -z "$new_id" ]]; then
  echo "FAIL: expected the survivor plus one new catalog replica after the scale-up, found $replica_count replica(s)"
  exit 1
fi
echo "PASS: catalog scaled back to 2 replicas, the new replica is healthy"

new_before="$(request_count "$new_id" /api/v1/catalog/)"
survivor_before="$(request_count "$survivor" /api/v1/catalog/)"
new_after="$new_before"
proof_deadline=$((SECONDS + PROOF_SECONDS))
while ((SECONDS < proof_deadline)); do
  sleep 2
  new_after="$(request_count "$new_id" /api/v1/catalog/)"
  [[ "$new_after" -gt "$new_before" ]] && break
done
survivor_after="$(request_count "$survivor" /api/v1/catalog/)"
log "catalog requests, new replica: $new_before -> $new_after, survivor: $survivor_before -> $survivor_after"
if [[ "$new_after" -le "$new_before" ]]; then
  echo "FAIL: the new catalog replica received no catalogue request within ${PROOF_SECONDS}s (new $new_before -> $new_after, survivor $survivor_before -> $survivor_after)"
  exit 1
fi
echo "PASS: the new catalog replica handled $((new_after - new_before)) catalogue requests (survivor $((survivor_after - survivor_before)))"

scale_total="$(wc -l <"$RESULTS" | tr -d ' ')"
scale_non2xx="$(tail -n +"$((scale_marker + 1))" "$RESULTS" | grep -vc '^2' || true)"
if [[ "$scale_non2xx" -gt "$GRACE" ]]; then
  echo "FAIL: $scale_non2xx non-2xx of $((scale_total - scale_marker)) requests while catalog was scaled back up (grace $GRACE)"
  tail -n +"$((scale_marker + 1))" "$RESULTS" | sort | uniq -c | sort -rn >&2
  exit 1
fi
echo "PASS: $scale_non2xx non-2xx of $((scale_total - scale_marker)) requests while catalog was scaled back up (grace $GRACE)"
stop_browse_loop

# --- Phase 2: a POST-heavy service. Identity with two replicas; sign-in with a wrong password answers 401.
log "scaling identity to 2"
scale identity 2
if ! wait_healthy "$WAIT_SECONDS" identity; then
  echo "FAIL: identity not healthy with 2 replicas within ${WAIT_SECONDS}s: ${WAIT_PENDING[*]}"
  exit 1
fi
identity_ids=()
while read -r id; do
  identity_ids+=("$id")
done < <(replica_ids identity)
if [[ ${#identity_ids[@]} -ne 2 ]]; then
  echo "FAIL: expected 2 identity replicas, found ${#identity_ids[@]}"
  exit 1
fi
echo "PASS: identity running with 2 healthy replicas"

POST_BEFORE="$(mktemp)"
POST_AFTER="$(mktemp)"
ident_victim="${identity_ids[0]}"
ident_survivor="${identity_ids[1]}"
sessions_victim_before="$(request_count "$ident_victim" /api/v1/identity/sessions)"
sessions_survivor_before="$(request_count "$ident_survivor" /api/v1/identity/sessions)"
: >"$POST_BEFORE"
post_burst 1 "$POST_ATTEMPTS" "$POST_BEFORE"
sessions_victim_after="$(request_count "$ident_victim" /api/v1/identity/sessions)"
sessions_survivor_after="$(request_count "$ident_survivor" /api/v1/identity/sessions)"
steady_ok="$(grep -c '^401$' "$POST_BEFORE" || true)"
steady_throttled="$(grep -c '^429$' "$POST_BEFORE" || true)"
steady_bad=$((POST_ATTEMPTS - steady_ok - steady_throttled))
log "steady-state sign-ins: $steady_ok x 401, $steady_throttled x 429, $steady_bad other; handled by the replicas: $((sessions_victim_after - sessions_victim_before)) and $((sessions_survivor_after - sessions_survivor_before))"
if [[ "$steady_bad" -gt 0 || "$steady_ok" -lt $((POST_ATTEMPTS / 2)) ]]; then
  echo "FAIL: sign-in with a wrong password did not answer 401 with two identity replicas ($steady_ok x 401, $steady_throttled x 429, $steady_bad other of $POST_ATTEMPTS)"
  sort "$POST_BEFORE" | uniq -c | sort -rn >&2
  rm -f "$POST_BEFORE" "$POST_AFTER"
  exit 1
fi
echo "PASS: $steady_ok x 401 and no 5xx from $POST_ATTEMPTS sign-ins with two identity replicas (replicas handled $((sessions_victim_after - sessions_victim_before)) and $((sessions_survivor_after - sessions_survivor_before)))"

log "stopping identity replica $ident_victim"
docker stop "$ident_victim" >/dev/null
: >"$POST_AFTER"
post_burst "$((POST_ATTEMPTS + 1))" "$POST_ATTEMPTS" "$POST_AFTER"
post_ok="$(grep -c '^401$' "$POST_AFTER" || true)"
post_throttled="$(grep -c '^429$' "$POST_AFTER" || true)"
post_bad=$((POST_ATTEMPTS - post_ok - post_throttled))
tail_bad="$(tail -n 3 "$POST_AFTER" | grep -vcE '^(401|429)$' || true)"
log "sign-ins after the stop: $post_ok x 401, $post_throttled x 429, $post_bad other"
failed=false
if [[ "$post_bad" -gt "$POST_GRACE" || "$tail_bad" -gt 0 ]]; then
  echo "FAIL: $post_bad of $POST_ATTEMPTS sign-ins after stopping one identity replica were not 401 (grace $POST_GRACE, $tail_bad of the last 3)"
  sort "$POST_AFTER" | uniq -c | sort -rn >&2
  failed=true
else
  echo "PASS: $post_bad non-401 of $POST_ATTEMPTS sign-ins after stopping one identity replica (grace $POST_GRACE; POST is never retried by the gateway)"
fi
rm -f "$POST_BEFORE" "$POST_AFTER"

# Leave the stack as it was found: a single identity replica (replicas without a shared IDENTITY_SIGNING_KEY would each
# publish their own key). Catalog stays at two replicas, like the start.
docker rm -f "$ident_victim" >/dev/null 2>&1 || true
scale identity 1
[[ "$failed" == "false" ]]
