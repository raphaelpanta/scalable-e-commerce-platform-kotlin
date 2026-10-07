# shellcheck shell=bash
# Configuration steps of `scripts/dev-env.sh init` (sourced, never executed): platform/compose/.env from the
# example, secrets, free gateway port, git hooks, Podman settings. Every write to .env goes through
# env_write_key: a temporary file in the same directory (mode 600) moved over the original, so an interrupted run
# never leaves a half-written file; secret values travel through pipes only, never on a command line, and are
# never printed. A non-empty value is never replaced. Bash 3.2 compatible.

ENV_FILE="$COMPOSE_DIR/.env"
ENV_EXAMPLE="$COMPOSE_DIR/.env.example"
ENV_REL="platform/compose/.env"
TC_PROPS="$HOME/.testcontainers.properties"
# shellcheck disable=SC2088  # display form of the path, never expanded
TC_PROPS_REL="~/.testcontainers.properties"
DEFAULT_GATEWAY_PORT=8080
TMP_FILES=""

# cleanup_tmp_files: EXIT trap of the main script; removes temporary files of interrupted writes.
cleanup_tmp_files() {
  local f
  for f in $TMP_FILES; do rm -f "$f"; done
}

# env_get KEY [FILE]: value of KEY in FILE (default .env, or the example while .env does not exist).
env_get() {
  local file="${2:-}"
  if [ -z "$file" ]; then
    if [ -f "$ENV_FILE" ]; then file="$ENV_FILE"; else file="$ENV_EXAMPLE"; fi
  fi
  grep -E "^$1=" "$file" 2>/dev/null | tail -n1 | cut -d= -f2- | sed 's/^"\(.*\)"$/\1/' || true
  return 0
}

# env_write_key KEY: set KEY in .env to the value read from stdin (newlines removed), atomically.
# The value must be non-empty. Never prints it.
env_write_key() {
  local key="$1" tmp
  [ -f "$ENV_FILE" ] || return 1
  tmp="$(mktemp "$COMPOSE_DIR/.env.tmp.XXXXXX")" || return 1
  TMP_FILES="$TMP_FILES $tmp"
  if ! awk -v key="$key" '
      NR == FNR { val = val $0; next }
      index($0, key "=") == 1 { print key "=" val; done = 1; next }
      { print }
      END { if (!done) print key "=" val; if (val == "") exit 1 }' - "$ENV_FILE" >"$tmp" 2>/dev/null; then
    rm -f "$tmp"
    return 1
  fi
  chmod 600 "$tmp"
  mv -f "$tmp" "$ENV_FILE"
}

# gateway_port_source: environment | env-file | default.
gateway_port_source() {
  if [ -n "${GATEWAY_PORT:-}" ]; then printf 'environment\n'
  elif [ -f "$ENV_FILE" ] && [ -n "$(env_get GATEWAY_PORT "$ENV_FILE")" ]; then printf 'env-file\n'
  else printf 'default\n'; fi
}

# effective_gateway_port: process environment, then .env, then 8080.
effective_gateway_port() {
  local port="${GATEWAY_PORT:-}"
  [ -n "$port" ] || port="$(env_get GATEWAY_PORT)"
  case "$port" in
    '' | *[!0-9]*) port="$DEFAULT_GATEWAY_PORT" ;;
  esac
  printf '%s\n' "$port"
}

# next_free_port START: the first free port after START (up to 100 tries).
next_free_port() {
  local p=$(( $1 + 1 )) end=$(( $1 + 100 ))
  while [ "$p" -le "$end" ]; do
    if port_free "$p"; then printf '%s\n' "$p"; return 0; fi
    p=$((p + 1))
  done
  return 1
}

env_is_ignored() { (cd "$REPO_ROOT" && git check-ignore -q "$ENV_REL") 2>/dev/null; }

step_env() {
  if ! env_is_ignored; then
    step_line FAIL env "$ENV_REL is not git-ignored: refusing to write secrets into a tracked path"
    error "$ENV_REL must be git-ignored (.gitignore) before the bootstrap writes secrets to it"
    exit 3
  fi
  if [ -f "$ENV_FILE" ]; then
    step_line OK env "$ENV_REL exists"
  elif [ "$DRY_RUN" = 1 ]; then
    step_line DRY-RUN env "create $ENV_REL from .env.example (mode 600)"
    dry_run_line "create $ENV_REL from .env.example (mode 600)"
  else
    (umask 077 && cp "$ENV_EXAMPLE" "$ENV_FILE")
    chmod 600 "$ENV_FILE"
    step_line CHANGE env "created $ENV_REL from .env.example (mode 600)"
  fi
}

# Secret generators: print one non-empty line; the value never leaves the pipe into env_write_key.
generate_identity_signing_key() {
  local k
  k="$(openssl genpkey -algorithm ed25519 -outform DER 2>/dev/null | base64 | tr -d '\n')" || return 1
  [ -n "$k" ] || return 1
  printf '%s\n' "$k"
}

generate_browser_session_key() {
  local k
  k="$(openssl rand -base64 32 2>/dev/null | tr -d '\n')" || return 1
  [ -n "$k" ] || return 1
  printf '%s\n' "$k"
}

step_secret() {
  local key status=OK text="" pending="" gen
  for key in IDENTITY_SIGNING_KEY BROWSER_SESSION_KEY; do
    if [ -f "$ENV_FILE" ] && [ -n "$(env_get "$key" "$ENV_FILE")" ]; then
      text="$text$key already set, "
      continue
    fi
    if [ "$DRY_RUN" = 1 ]; then
      status=DRY-RUN
      text="$text$key would be generated, "
      pending="$pending$key "
      continue
    fi
    case "$key" in
      IDENTITY_SIGNING_KEY) gen=generate_identity_signing_key ;;
      *) gen=generate_browser_session_key ;;
    esac
    if "$gen" | env_write_key "$key"; then
      status=CHANGE
      text="$text$key generated, "
    else
      step_line FAIL secret "could not generate $key (openssl failed); $ENV_REL unchanged"
      exit 3
    fi
  done
  step_line "$status" secret "${text%, }"
  for key in $pending; do dry_run_line "generate $key in $ENV_REL"; done
}

step_port() {
  local port source default free
  port="$(effective_gateway_port)"
  source="$(gateway_port_source)"
  default="$(env_get GATEWAY_PORT "$ENV_EXAMPLE")"
  default="${default:-$DEFAULT_GATEWAY_PORT}"
  if port_free "$port" || port_is_own_gateway "$port"; then
    step_line OK port "GATEWAY_PORT $port free"
    return 0
  fi
  if [ "$source" = environment ]; then
    step_line SKIP port "GATEWAY_PORT $port from the environment is in use (not changed)"
    return 0
  fi
  if [ "$port" != "$default" ]; then
    step_line SKIP port "GATEWAY_PORT $port chosen by you is in use (not changed)"
    return 0
  fi
  if ! free="$(next_free_port "$port")"; then
    step_line SKIP port "GATEWAY_PORT $port in use and no free port found after it"
    return 0
  fi
  if [ "$DRY_RUN" = 1 ]; then
    step_line DRY-RUN port "GATEWAY_PORT $port in use, would record $free in $ENV_REL"
    dry_run_line "set GATEWAY_PORT=$free in $ENV_REL"
  elif [ -f "$ENV_FILE" ] && printf '%s\n' "$free" | env_write_key GATEWAY_PORT; then
    step_line CHANGE port "GATEWAY_PORT $port in use, recorded $free in $ENV_REL"
  else
    step_line SKIP port "GATEWAY_PORT $port in use, could not record $free in $ENV_REL"
  fi
}

step_hooks() {
  local current
  current="$(cd "$REPO_ROOT" && git config core.hooksPath 2>/dev/null || true)"
  if [ "$current" = .githooks ]; then
    step_line OK hooks "git config core.hooksPath .githooks"
  elif [ "$DRY_RUN" = 1 ]; then
    step_line DRY-RUN hooks "git config core.hooksPath .githooks"
    (cd "$REPO_ROOT" && run git config core.hooksPath .githooks)
  else
    (cd "$REPO_ROOT" && run git config core.hooksPath .githooks)
    step_line CHANGE hooks "git config core.hooksPath .githooks"
  fi
}

step_engine() {
  case "$ENGINE" in
    podman) ;;
    docker) step_line OK engine "engine is docker, nothing to set"; return 0 ;;
    *) step_line SKIP engine "no engine reachable"; return 0 ;;
  esac
  local value
  value="$(env_get BUILDAH_FORMAT)"
  if [ "${BUILDAH_FORMAT:-}" = docker ] || [ "$value" = docker ]; then
    step_line OK engine "BUILDAH_FORMAT=docker already set"
    export BUILDAH_FORMAT=docker
  elif [ -n "${BUILDAH_FORMAT:-}" ] || [ -n "$value" ]; then
    step_line SKIP engine "BUILDAH_FORMAT=${BUILDAH_FORMAT:-$value} set by you (not changed); Podman needs docker"
  elif [ "$DRY_RUN" = 1 ]; then
    step_line DRY-RUN engine "record BUILDAH_FORMAT=docker in $ENV_REL"
    dry_run_line "set BUILDAH_FORMAT=docker in $ENV_REL"
  elif [ -f "$ENV_FILE" ] && printf 'docker\n' | env_write_key BUILDAH_FORMAT; then
    step_line CHANGE engine "BUILDAH_FORMAT=docker recorded in $ENV_REL"
    export BUILDAH_FORMAT=docker
  else
    step_line SKIP engine "could not record BUILDAH_FORMAT=docker in $ENV_REL"
  fi
  note_line "export BUILDAH_FORMAT=docker in your shell profile for manual compose commands (this script never edits shell profiles)"
}

# ryuk_consent: --yes, or an interactive `y`.
ryuk_consent() {
  [ "$YES" = 1 ] && return 0
  is_interactive || return 1
  local answer
  printf 'Set ryuk.disabled=true in %s (outside the repository)? [y/N]: ' "$TC_PROPS_REL"
  read_answer answer
  case "$answer" in y | Y | yes) return 0 ;; esac
  return 1
}

write_ryuk_disabled() {
  local tmp
  mkdir -p "$(dirname "$TC_PROPS")"
  tmp="$(mktemp "$(dirname "$TC_PROPS")/.testcontainers.properties.XXXXXX")" || return 1
  TMP_FILES="$TMP_FILES $tmp"
  if [ -f "$TC_PROPS" ]; then grep -v '^ryuk.disabled=' "$TC_PROPS" >"$tmp" || true; fi
  printf 'ryuk.disabled=true\n' >>"$tmp"
  mv -f "$tmp" "$TC_PROPS"
}

step_ryuk() {
  case "$ENGINE" in
    podman) ;;
    docker) step_line OK ryuk "engine is docker, nothing to set"; return 0 ;;
    *) step_line SKIP ryuk "no engine reachable"; return 0 ;;
  esac
  if ryuk_disabled; then
    step_line OK ryuk "ryuk.disabled=true in $TC_PROPS_REL"
  elif ! ryuk_consent; then
    step_line SKIP ryuk "$TC_PROPS_REL: consent not given (rerun with --yes)"
  elif [ "$DRY_RUN" = 1 ]; then
    step_line DRY-RUN ryuk "set ryuk.disabled=true in $TC_PROPS_REL"
    dry_run_line "set ryuk.disabled=true in $TC_PROPS_REL"
  elif write_ryuk_disabled; then
    step_line CHANGE ryuk "ryuk.disabled=true set in $TC_PROPS_REL"
  else
    step_line SKIP ryuk "could not write $TC_PROPS_REL"
  fi
}

# configure_clone: the six steps in contract order.
configure_clone() {
  step_env
  step_secret
  step_port
  step_hooks
  step_engine
  step_ryuk
}
