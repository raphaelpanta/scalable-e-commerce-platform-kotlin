# shellcheck shell=bash
# The 16 environment checks of scripts/dev-env.sh (sourced, never executed). A check changes nothing, finishes within
# PROBE_TIMEOUT seconds per probe and prints one check line (plus fix or note lines). Order, names, expected values
# and fix texts: specs/005-storefront-dev-bootstrap/contracts/dev-env-cli.md, "Checks (16)". Bash 3.2 compatible.

JDK_PIN="$(tr -d '[:space:]' <"$REPO_ROOT/.java-version" 2>/dev/null || true)"
JDK_PIN="${JDK_PIN:-25}"
NODE_PIN="$(tr -d '[:space:]v' <"$REPO_ROOT/frontend/.nvmrc" 2>/dev/null || true)"
NODE_PIN="$(major_of "${NODE_PIN:-24}")"
NPM_MIN=11
GIT_MIN=2.9
MEM_MIN_GIB=10
CPU_MIN=4
DISK_MIN_GIB=15
REGISTRY_PROBE_URL="https://registry-1.docker.io/v2/"

# Checks in contract order: name:kind (P prerequisite, C configuration).
CHECK_ORDER="jdk:P engine:P compose:P memory:P cpus:P disk:P podman:C node:P npm:P gitleaks:P curl:P jq:P openssl:P git:P port:C network:P"

# fixes MACOS_FIX LINUX_FIX...: the fix lines of the detected operating system.
fixes() {
  local mac="$1" l
  shift
  if [ "$OS" = macos ]; then
    fix_line macos "$mac"
  else
    for l in "$@"; do fix_line linux "$l"; done
  fi
}

# tool_version TOOL ARGS...: stdout+stderr of a version command, empty when the tool is missing or fails.
tool_version() {
  local out
  if out="$(with_timeout "$PROBE_TIMEOUT" "$@" 2>&1)"; then printf '%s\n' "$out"; fi
}

# provisioned_jdk PIN: a JDK of the pinned major that the build can use although it is not the default `java`:
# a toolchain Gradle provisioned (~/.gradle/jdks), an SDKMAN candidate, or (macOS) the one `java_home -v PIN`
# resolves. Prints "<version> via <source>" for the first match; DEV_ENV_JAVA_HOME_TOOL overrides the java_home path.
provisioned_jdk() {
  local pin="$1" rel ver d tool home
  # Gradle unpacks a toolchain as <id>/release, <id>/jdk-x/release or <id>/jdk-x/Contents/Home/release (macOS).
  for rel in $(find "$HOME/.gradle/jdks" -maxdepth 5 -name release -type f 2>/dev/null | sort); do
    ver="$(sed -n 's/^JAVA_VERSION="\([^"]*\)".*/\1/p' "$rel" | head -n1)"
    if [ "$(java_major_of "$ver")" = "$pin" ]; then printf '%s via Gradle toolchain\n' "$ver"; return 0; fi
  done
  for d in "$HOME"/.sdkman/candidates/java/"$pin"*; do
    [ -d "$d" ] || continue
    printf '%s via SDKMAN\n' "$(basename "$d")"
    return 0
  done
  tool="${DEV_ENV_JAVA_HOME_TOOL:-/usr/libexec/java_home}"
  if [ -x "$tool" ] && home="$(with_timeout "$PROBE_TIMEOUT" "$tool" -v "$pin" 2>/dev/null)" && [ -f "$home/release" ]; then
    ver="$(sed -n 's/^JAVA_VERSION="\([^"]*\)".*/\1/p' "$home/release" | head -n1)"
    if [ "$(java_major_of "$ver")" = "$pin" ]; then printf '%s via java_home\n' "$ver"; return 0; fi
  fi
  return 1
}

check_jdk() {
  local ver other
  ver="$(tool_version java -version | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n1)"
  if [ -n "$ver" ] && [ "$(java_major_of "$ver")" = "$JDK_PIN" ]; then
    check_line PASS jdk "$ver" "$JDK_PIN"
  elif [ -n "$ver" ] && other="$(provisioned_jdk "$JDK_PIN")"; then
    # Gradle runs on the pinned toolchain even when the default java differs (docs/build.md).
    check_line PASS jdk "$ver (default), $other" "$JDK_PIN"
  else
    check_line FAIL jdk "$ver" "$JDK_PIN"
    fixes "sdk env install" "sdk env install"
  fi
}

check_engine() {
  if detect_engine; then
    local found="$ENGINE $ENGINE_VERSION"
    [ "$ENGINE_SHIM" = 0 ] || found="$found (docker socket)"
    check_line PASS engine "$found" "docker or podman reachable"
  else
    check_line FAIL engine "" "docker or podman reachable"
    fixes "install Docker Desktop, or brew install podman && podman machine init && podman machine start" \
      "install podman or docker engine, start the service"
  fi
}

check_compose() {
  if detect_compose_cmd && [ "$(major_of "$COMPOSE_VERSION")" -ge 2 ]; then
    check_line PASS compose "v$COMPOSE_VERSION (${COMPOSE[*]})" "v2"
  else
    local found=""
    [ -z "$COMPOSE_VERSION" ] || found="v$COMPOSE_VERSION"
    check_line FAIL compose "$found" "v2"
    fixes "brew install docker-compose (or enable Compose in Docker Desktop)" \
      "sudo apt-get install docker-compose-plugin" "sudo dnf install docker-compose-plugin" \
      "or podman-compose as the provider: COMPOSE_CMD='podman compose'"
  fi
}

check_memory() {
  [ -n "$ENGINE_BIN" ] || { skip_line memory "no engine"; return; }
  local bytes gib
  bytes="$(engine_memory_bytes || true)"
  gib="$(gib_of "${bytes:-0}")"
  if [ -n "$bytes" ] && [ "$gib" -ge "$MEM_MIN_GIB" ]; then
    check_line PASS memory "$gib GiB" ">= $MEM_MIN_GIB GiB"
  else
    check_line FAIL memory "${bytes:+$gib GiB}" ">= $MEM_MIN_GIB GiB"
    fixes "podman machine set --memory 10240, or raise the memory in Docker Desktop > Settings > Resources" \
      "free memory on the host or add RAM (the engine uses the host memory)"
  fi
}

check_cpus() {
  [ -n "$ENGINE_BIN" ] || { skip_line cpus "no engine"; return; }
  local n
  n="$(engine_cpus || true)"
  if [ -n "$n" ] && [ "$n" -ge "$CPU_MIN" ] 2>/dev/null; then
    check_line PASS cpus "$n" ">= $CPU_MIN"
  else
    check_line FAIL cpus "$n" ">= $CPU_MIN"
    fixes "podman machine set --cpus 4, or raise the CPUs in Docker Desktop > Settings > Resources" \
      "n/a (the engine uses the host CPUs)"
  fi
}

# free_gib_of PATH: free GiB of the filesystem holding PATH (empty when unknown).
free_gib_of() {
  local kb
  kb="$(df -Pk "$1" 2>/dev/null | awk 'NR == 2 { print $4 }' || true)"
  [ -n "$kb" ] || return 0
  printf '%s\n' $(( kb / 1048576 ))
}

check_disk() {
  local repo_free engine_free="" root lowest where="repository"
  repo_free="$(free_gib_of "$REPO_ROOT")"
  if [ -n "$ENGINE_BIN" ]; then
    root="$(engine_root_dir || true)"
    [ -n "$root" ] && [ -d "$root" ] && engine_free="$(free_gib_of "$root")"
  fi
  lowest="$repo_free"
  if [ -n "$engine_free" ] && [ -n "$lowest" ] && [ "$engine_free" -lt "$lowest" ]; then
    lowest="$engine_free"
    where="engine storage"
  fi
  if [ -n "$lowest" ] && [ "$lowest" -ge "$DISK_MIN_GIB" ]; then
    check_line PASS disk "$lowest GiB free ($where)" ">= $DISK_MIN_GIB GiB free"
  else
    check_line FAIL disk "${lowest:+$lowest GiB free ($where)}" ">= $DISK_MIN_GIB GiB free"
    fixes "free disk space; podman system prune or docker image prune (images of other projects are yours to judge)" \
      "free disk space; podman system prune or docker image prune (images of other projects are yours to judge)"
  fi
}

# buildah_format_effective: BUILDAH_FORMAT=docker from the environment or platform/compose/.env.
buildah_format_effective() {
  [ "${BUILDAH_FORMAT:-}" = docker ] && return 0
  [ "$(env_get BUILDAH_FORMAT)" = docker ]
}

ryuk_disabled() { grep -q '^ryuk.disabled=true' "$HOME/.testcontainers.properties" 2>/dev/null; }

check_podman() {
  case "$ENGINE" in
    podman) ;;
    docker) skip_line podman "engine is docker"; return ;;
    *) skip_line podman "no engine"; return ;;
  esac
  local fmt ryuk value
  value="${BUILDAH_FORMAT:-$(env_get BUILDAH_FORMAT)}"
  if buildah_format_effective; then fmt="BUILDAH_FORMAT=docker"
  elif [ -n "$value" ]; then fmt="BUILDAH_FORMAT=$value"
  else fmt="BUILDAH_FORMAT unset"; fi
  if ryuk_disabled; then ryuk="ryuk disabled"; else ryuk="ryuk enabled"; fi
  if buildah_format_effective && ryuk_disabled; then
    check_line PASS podman "$fmt, $ryuk" "BUILDAH_FORMAT=docker, ryuk disabled"
  else
    check_line FAIL podman "$fmt, $ryuk" "BUILDAH_FORMAT=docker, ryuk disabled"
    fixes "run: scripts/dev-env.sh init" "run: scripts/dev-env.sh init"
  fi
  [ "$ENGINE_SHIM" = 0 ] ||
    note_line "Podman answers through a Docker-compatible socket; the Podman settings apply (the docker CLI keeps working)"
  note_line "rootless Podman caps concurrent containers through the kernel keyring quota (kernel.keys.maxkeys); raise it in the machine when the 20th container fails to start"
}

check_node() {
  local ver
  ver="$(tool_version node --version | head -n1 | tr -d 'v')"
  if [ -n "$ver" ] && [ "$(major_of "$ver")" = "$NODE_PIN" ]; then
    check_line PASS node "$ver" "$NODE_PIN"
  else
    check_line FAIL node "$ver" "$NODE_PIN"
    fixes "brew install node@$NODE_PIN" "fnm install $NODE_PIN" "or the nodejs package of your distribution (major $NODE_PIN)"
  fi
}

check_npm() {
  local ver
  ver="$(tool_version npm --version | head -n1)"
  if [ -n "$ver" ] && [ "$(major_of "$ver")" -ge "$NPM_MIN" ]; then
    check_line PASS npm "$ver" ">= $NPM_MIN"
  else
    check_line FAIL npm "$ver" ">= $NPM_MIN"
    fixes "reinstall Node $NODE_PIN (brew install node@$NODE_PIN)" "reinstall Node $NODE_PIN (fnm install $NODE_PIN or the distribution package)"
  fi
}

check_gitleaks() {
  local ver
  ver="$(tool_version gitleaks version | head -n1 | tr -d 'v')"
  if [ -n "$ver" ]; then
    check_line PASS gitleaks "$ver" installed
  else
    check_line FAIL gitleaks "" installed
    fixes "brew install gitleaks" "the release binary of gitleaks (https://github.com/gitleaks/gitleaks/releases) or the distribution package (sudo dnf install gitleaks)"
  fi
}

check_curl() {
  local ver
  ver="$(tool_version curl --version | head -n1 | awk '{ print $2 }')"
  if [ -n "$ver" ]; then
    check_line PASS curl "$ver" installed
  else
    check_line FAIL curl "" installed
    fixes "brew install curl" "sudo apt-get install curl" "sudo dnf install curl"
  fi
}

check_jq() {
  local ver
  ver="$(tool_version jq --version | head -n1 | sed 's/^jq-//')"
  if [ -n "$ver" ]; then
    check_line PASS jq "$ver" installed
  else
    check_line FAIL jq "" installed
    fixes "brew install jq" "sudo apt-get install jq" "sudo dnf install jq"
  fi
}

check_openssl() {
  local ver
  ver="$(tool_version openssl version | head -n1 | awk '{ print $2 }')"
  if [ -n "$ver" ] && with_timeout "$PROBE_TIMEOUT" openssl genpkey -algorithm ed25519 >/dev/null 2>&1; then
    check_line PASS openssl "$ver" "ed25519 capable"
  else
    check_line FAIL openssl "${ver:+$ver (no ed25519)}" "ed25519 capable"
    fixes "brew install openssl" "sudo apt-get install openssl" "sudo dnf install openssl"
  fi
}

check_git() {
  local ver
  ver="$(tool_version git --version | head -n1 | awk '{ print $3 }')"
  if [ -n "$ver" ] && version_ge "$ver" "$GIT_MIN"; then
    check_line PASS git "$ver" ">= $GIT_MIN"
  else
    check_line FAIL git "$ver" ">= $GIT_MIN"
    fixes "brew install git" "sudo apt-get install git" "sudo dnf install git"
  fi
}

# port_free PORT: a TCP connection to 127.0.0.1:PORT is refused.
port_free() {
  local rc=0
  with_timeout "$PROBE_TIMEOUT" curl -s -o /dev/null --connect-timeout "$PROBE_TIMEOUT" --max-time "$PROBE_TIMEOUT" \
    "http://127.0.0.1:$1/" >/dev/null 2>&1 || rc=$?
  [ "$rc" = 7 ]
}

# port_is_own_gateway PORT: the listener on PORT is the platform's gateway container.
port_is_own_gateway() {
  [ "${#COMPOSE[@]}" -gt 0 ] || return 1
  platform_ps_json 2>/dev/null |
    jq -e --argjson p "$1" '.[] | select(.Service == "gateway") | (.Publishers // [])[] | select(.PublishedPort == $p)' >/dev/null 2>&1
}

check_port() {
  local port
  port="$(effective_gateway_port)"
  if port_free "$port"; then
    check_line PASS port "$port free" "$port free"
  elif port_is_own_gateway "$port"; then
    check_line PASS port "$port in use (platform gateway)" "$port free"
  else
    check_line FAIL port "$port in use" "$port free"
    fixes "init proposes a free port, or set GATEWAY_PORT in platform/compose/.env" \
      "init proposes a free port, or set GATEWAY_PORT in platform/compose/.env"
  fi
}

check_network() {
  local code rc=0
  code="$(with_timeout "$PROBE_TIMEOUT" curl -s -o /dev/null -w '%{http_code}' --connect-timeout "$PROBE_TIMEOUT" \
    --max-time "$PROBE_TIMEOUT" "$REGISTRY_PROBE_URL" 2>/dev/null)" || rc=$?
  if [ "$rc" = 0 ] && [ -n "$code" ] && [ "$code" != 000 ]; then
    check_line PASS network "HTTP $code" "registry reachable"
  else
    skip_line network "no network"
  fi
}

# run_checks KIND: all | P | C, or a space-separated list of names; prints the lines in contract order.
run_checks() {
  local want="$1" entry name kind
  for entry in $CHECK_ORDER; do
    name="${entry%%:*}"
    kind="${entry#*:}"
    case " $want " in
      " all ") ;;
      " P " | " C ") [ "$kind" = "$want" ] || continue ;;
      *" $name "*) ;;
      *) continue ;;
    esac
    "check_$name"
  done
}

# prerequisite_failed: a prerequisite (P) check is among the failed ones.
prerequisite_failed() {
  local name entry
  for name in $FAILED_CHECKS; do
    for entry in $CHECK_ORDER; do
      [ "${entry%%:*}" = "$name" ] && [ "${entry#*:}" = P ] && return 0
    done
  done
  return 1
}

# failed_prerequisites: the failed P checks, space separated.
failed_prerequisites() {
  local name entry out=""
  for name in $FAILED_CHECKS; do
    for entry in $CHECK_ORDER; do
      [ "${entry%%:*}" = "$name" ] && [ "${entry#*:}" = P ] && out="$out $name"
    done
  done
  printf '%s\n' "${out# }"
}
