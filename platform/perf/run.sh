#!/usr/bin/env bash
# Runs the performance suite (T115, SC-002/SC-003) against the running Compose stack with the grafana/k6 container.
# Loads the 10,000-product dataset first (idempotent), then runs browse-and-checkout.js and writes
# results/summary.json. Exit status: 0 all thresholds met, 99 a threshold failed (k6), 2 bad usage or pre-flight.
#
# Usage: run.sh [--no-seed] [-h] [-- <extra k6 arguments>]
#   --no-seed  do not apply seed-10k.sql (the dataset is loaded already, or on purpose another one is used)
#
# Environment (all optional):
#   GATEWAY_URL http://localhost:8080   MAILPIT_URL http://localhost:8025   BROWSE_VUS 1000   CHECKOUT_VUS 100
#   DURATION 3m (hold time)   RAMP_UP 2m   THINK_TIME 1   CHECKOUT_PACE 5   (see browse-and-checkout.js)
#   K6_IMAGE           image to run (default docker.io/grafana/k6:1.7.0)
#   CONTAINER_ENGINE   docker or podman (default: docker when present, else podman)
#   K6_HOST            host name the container uses for the machine's localhost (macOS/Windows engines; default
#                      host.docker.internal, host.containers.internal with Podman). On Linux the container shares the
#                      host network and URLs are used as given.
#   K6_NETWORK         run k6 inside this container network (e.g. ecommerce-platform_internal) against
#                      K6_GATEWAY_URL (default http://gateway:8080) and K6_MAILPIT_URL (default http://mailpit:8025);
#                      GATEWAY_URL stays the host-side address used by the pre-flight and the seed. The load then skips
#                      the VM's host port-forward, which caps a macOS Podman machine at roughly 150 requests per second
#   COMPOSE_CMD        Compose command used by seed-10k-apply.sh (default "docker compose")
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_IMAGE="${K6_IMAGE:-docker.io/grafana/k6:1.7.0}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
SEED=true
K6_ARGS=()

usage() {
  sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-seed) SEED=false ;;
    -h | --help) usage; exit 0 ;;
    --) shift; K6_ARGS=("$@"); break ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ -n "${CONTAINER_ENGINE:-}" ]]; then
  ENGINE="$CONTAINER_ENGINE"
elif command -v docker >/dev/null 2>&1; then
  ENGINE=docker
elif command -v podman >/dev/null 2>&1; then
  ENGINE=podman
else
  echo "FAIL: neither docker nor podman found (set CONTAINER_ENGINE)" >&2
  exit 2
fi

# status_of <url>: HTTP status code, 000 when the connection fails.
status_of() {
  curl -s -o /dev/null -w '%{http_code}' --connect-timeout 3 --max-time 10 "$1" || true
}

if [[ "$(status_of "$GATEWAY_URL/api/v1/catalog/products?size=1")" != "200" ]]; then
  echo "FAIL: the gateway does not answer 200 at $GATEWAY_URL; start the stack first (README.md, prerequisites)" >&2
  exit 2
fi
if [[ "${CHECKOUT_VUS:-100}" != "0" && "$(status_of "$MAILPIT_URL/api/v1/info")" != "200" ]]; then
  echo "FAIL: Mailpit does not answer at $MAILPIT_URL (the checkout shoppers read their verification email there)" >&2
  exit 2
fi

if [[ "$SEED" == "true" ]]; then
  "$SCRIPT_DIR/seed-10k-apply.sh"
fi

# Reaching the stack from the container: Linux shares the host network; elsewhere the engine runs in a VM and the
# machine's localhost is a host alias.
NETWORK=()
MOUNT_OPTIONS=""
CONTAINER_GATEWAY_URL="$GATEWAY_URL"
CONTAINER_MAILPIT_URL="$MAILPIT_URL"
if [[ -n "${K6_NETWORK:-}" ]]; then
  # Inside the stack's own network: the pre-flight and the seed still use GATEWAY_URL from the host, k6 talks to the
  # containers by service name, so no port-forward sits in the measured path.
  NETWORK=(--network "$K6_NETWORK")
  CONTAINER_GATEWAY_URL="${K6_GATEWAY_URL:-http://gateway:8080}"
  CONTAINER_MAILPIT_URL="${K6_MAILPIT_URL:-http://mailpit:8025}"
  if "$ENGINE" --version 2>/dev/null | grep -qi podman; then
    MOUNT_OPTIONS=":z"
  fi
elif [[ "$(uname -s)" == "Linux" ]]; then
  NETWORK=(--network host)
  if "$ENGINE" --version 2>/dev/null | grep -qi podman; then
    MOUNT_OPTIONS=":z"
  fi
else
  if [[ -n "${K6_HOST:-}" ]]; then
    HOST_ALIAS="$K6_HOST"
  elif "$ENGINE" --version 2>/dev/null | grep -qi podman; then
    HOST_ALIAS="host.containers.internal"
  else
    HOST_ALIAS="host.docker.internal"
  fi
  CONTAINER_GATEWAY_URL="${GATEWAY_URL/localhost/$HOST_ALIAS}"
  CONTAINER_GATEWAY_URL="${CONTAINER_GATEWAY_URL/127.0.0.1/$HOST_ALIAS}"
  CONTAINER_MAILPIT_URL="${MAILPIT_URL/localhost/$HOST_ALIAS}"
  CONTAINER_MAILPIT_URL="${CONTAINER_MAILPIT_URL/127.0.0.1/$HOST_ALIAS}"
fi

# The container user is not the owner of the mounted directory: let it write the summary.
mkdir -p "$SCRIPT_DIR/results"
chmod 777 "$SCRIPT_DIR/results"

ENV_ARGS=(-e "GATEWAY_URL=$CONTAINER_GATEWAY_URL" -e "MAILPIT_URL=$CONTAINER_MAILPIT_URL")
for name in BROWSE_VUS CHECKOUT_VUS DURATION RAMP_UP THINK_TIME CHECKOUT_PACE PERF_PRODUCTS MIN_PRODUCTS MAIL_WAIT_S \
  MAX_429_RETRIES SUMMARY_PATH; do
  if [[ -n "${!name:-}" ]]; then
    ENV_ARGS+=(-e "$name=${!name}")
  fi
done

echo "k6 ($K6_IMAGE) via $ENGINE against $CONTAINER_GATEWAY_URL; summary: $SCRIPT_DIR/results/summary.json"
status=0
# 1,000 virtual users need many sockets: raise the descriptor limit of the container.
"$ENGINE" run --rm --ulimit nofile=65536:65536 \
  ${NETWORK[@]+"${NETWORK[@]}"} \
  -v "$SCRIPT_DIR:/perf$MOUNT_OPTIONS" -w /perf \
  "${ENV_ARGS[@]}" \
  "$K6_IMAGE" run ${K6_ARGS[@]+"${K6_ARGS[@]}"} /perf/browse-and-checkout.js || status=$?

if [[ "$status" -eq 0 ]]; then
  echo "PASS: every threshold was met"
elif [[ "$status" -eq 99 ]]; then
  echo "FAIL: at least one threshold was crossed (see the list above and results/summary.json)" >&2
else
  echo "FAIL: k6 exited with status $status" >&2
fi
exit "$status"
