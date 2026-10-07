#!/usr/bin/env bash
# Fixture for scripts/tests/test_dev_env_*.sh (source after lib/assert.sh): a temporary clone with the bootstrap
# script, a temporary copy of platform/compose and platform/ci-runner, a temporary HOME, a real git repository (so
# `git check-ignore` and `git config core.hooksPath` work) and PATH with the stubs of scripts/tests/stubs first.
# Nothing here contacts a container engine or the network: every tool the script calls is a stub.
#
#   dev_env_fixture        create a fresh fixture: sets P (clone), H (home), S (stub state), STUB_LOG, ENV_FILE
#   dev_env ARGS...        run the clone's scripts/dev-env.sh with stdin from /dev/null
#   dev_env_in ANSWERS ARGS...   same, interactive, with ANSWERS fed through stdin (one per line)
#   configured_clone       run `init` once so later runs start from a configured clone
#   stub_log_grep PATTERN  lines of the stub log matching PATTERN (empty when none)
#   stub_log_count PATTERN number of matching lines
#   env_value KEY          value of KEY in the clone's platform/compose/.env

STUBS_DIR="$TESTS_DIR/stubs"
REAL_JQ="$(command -v jq)"
REAL_GIT="$(command -v git)"
[ -n "$REAL_JQ" ] || { echo "jq must be installed to run the dev-env tests (brew install jq)" >&2; exit 1; }

unset_stub_vars() {
  local v
  for v in $(compgen -v | grep '^STUB_' || true); do
    case "$v" in STUB_LOG | STUB_STATE | STUB_REAL_JQ | STUB_REAL_GIT) ;; *) unset "$v" ;; esac
  done
  unset GATEWAY_PORT COMPOSE_CMD CONTAINER_ENGINE BUILDAH_FORMAT
}

dev_env_fixture() {
  local t
  t="$(mk_tmp)"
  P="$t/clone"
  H="$t/home"
  S="$t/stub-state"
  mkdir -p "$P/scripts" "$P/platform/compose" "$P/platform/ci-runner" "$P/frontend" "$H" "$S"
  cp "$PROJECT_ROOT/scripts/dev-env.sh" "$P/scripts/"
  cp -R "$PROJECT_ROOT/scripts/lib" "$P/scripts/lib"
  cp "$PROJECT_ROOT/platform/compose/docker-compose.yml" "$PROJECT_ROOT/platform/compose/.env.example" "$P/platform/compose/"
  cp -R "$PROJECT_ROOT/platform/compose/scripts" "$P/platform/compose/scripts"
  cp "$PROJECT_ROOT/platform/ci-runner/docker-compose.yml" "$PROJECT_ROOT/platform/ci-runner/.env.example" "$P/platform/ci-runner/"
  cp "$PROJECT_ROOT/.gitignore" "$PROJECT_ROOT/.java-version" "$PROJECT_ROOT/.sdkmanrc" "$P/"
  cp -R "$PROJECT_ROOT/.githooks" "$P/.githooks"
  if [ -f "$PROJECT_ROOT/frontend/.nvmrc" ]; then cp "$PROJECT_ROOT/frontend/.nvmrc" "$P/frontend/"; else echo 24 >"$P/frontend/.nvmrc"; fi
  "$REAL_GIT" init -q -b main "$P"
  ENV_FILE="$P/platform/compose/.env"
  STUB_LOG="$t/stub.log"
  : >"$STUB_LOG"
  unset_stub_vars
  export STUB_LOG STUB_STATE="$S" STUB_REAL_JQ="$REAL_JQ" STUB_REAL_GIT="$REAL_GIT"
  export HOME="$H" NO_COLOR=1 DEV_ENV_NON_INTERACTIVE=1 DEV_ENV_WAIT_SECONDS=1 DEV_ENV_POLL_SECONDS=1 DEV_ENV_SMOKE_SECONDS=1
  export DEV_ENV_JAVA_HOME_TOOL="$H/no-java_home" # the real macOS java_home must not leak into the jdk check
  export PATH="$STUBS_DIR:/usr/bin:/bin:/usr/sbin:/sbin"
}

dev_env() { "$P/scripts/dev-env.sh" "$@" </dev/null; }

dev_env_in() { # ANSWERS ARGS...
  local answers="$1"
  shift
  printf '%s\n' "$answers" | DEV_ENV_NON_INTERACTIVE=0 "$P/scripts/dev-env.sh" "$@"
}

configured_clone() {
  dev_env init >/dev/null 2>&1 || { echo "fixture: initial init failed" >&2; dev_env init; return 1; }
  : >"$STUB_LOG"
}

stub_log_grep() { grep -E -- "$1" "$STUB_LOG" 2>/dev/null || true; }
stub_log_count() { grep -c -E -- "$1" "$STUB_LOG" 2>/dev/null || true; }

env_value() { # KEY
  grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2-
}

# count_lines TEXT PREFIX: number of lines of TEXT starting with PREFIX.
count_lines() { printf '%s\n' "$1" | grep -c "^$2" || true; }
