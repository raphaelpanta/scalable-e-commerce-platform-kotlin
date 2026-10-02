#!/usr/bin/env bash
# Runs the CI runner with a truly fresh container per job and without a long-lived credential in the container:
# for every iteration it fetches a one-hour registration token with the host's `gh` login (RUNNER_TOKEN), starts a
# newly created `runner` container (RUNNER_RESTART=no) and waits until it exits, which an ephemeral runner does after
# one job. The runner container is then removed and the work directory emptied. Leave ACCESS_TOKEN empty in .env.
# Run it under a process supervisor (systemd service, tmux, nohup). Stop it with Ctrl-C or SIGTERM.
#
# Usage: scripts/run-ephemeral.sh [--once] [-h]
#   --once  serve a single job and exit (smoke test of the registration)
# Needs: gh (logged in with a token that may create registration tokens for the repository), docker compose.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONCE=false
for arg in "$@"; do
  case "$arg" in
    --once) ONCE=true ;;
    -h | --help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

env_value() { { grep -E "^$1=" "$DIR/.env" || true; } | head -n 1 | cut -d= -f2- | sed 's/^"//; s/"$//'; }
[[ -f "$DIR/.env" ]] || { echo "missing $DIR/.env (copy .env.example)" >&2; exit 1; }
command -v gh >/dev/null 2>&1 || { echo "gh is required" >&2; exit 1; }
repo_url="$(env_value REPO_URL)"
repo="${repo_url#https://github.com/}"
repo="${repo%/}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "REPO_URL must be https://github.com/<owner>/<repo>" >&2; exit 1; }
[[ -n "$(env_value RUNNER_WORKDIR)" ]] || { echo "RUNNER_WORKDIR is not set in .env" >&2; exit 1; }

if docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi
stop=false
trap 'stop=true' INT TERM

cd "$DIR"
while [[ "$stop" == "false" ]]; do
  token="$(gh api -X POST "repos/$repo/actions/runners/registration-token" --jq .token)"
  RUNNER_TOKEN="$token" ACCESS_TOKEN="" RUNNER_RESTART=no \
    "${compose[@]}" up --force-recreate --no-deps --abort-on-container-exit runner || true
  "${compose[@]}" rm -f -s runner >/dev/null 2>&1 || true
  # The work directory is owned by root (the runner runs as root): empty it through a throw-away runner container.
  # shellcheck disable=SC2016  # the variable is expanded by the shell inside the container
  RUNNER_TOKEN="" ACCESS_TOKEN="" RUNNER_RESTART=no "${compose[@]}" run --rm -T --no-deps --entrypoint sh runner \
    -c 'rm -rf "${RUNNER_WORKDIR:?}"/* "${RUNNER_WORKDIR:?}"/.[!.]*' >/dev/null 2>&1 || true
  [[ "$ONCE" == "false" ]] || break
done
