# shellcheck shell=bash
# Opt-in installs of `scripts/dev-env.sh init --install` (sourced, never executed). Each failing prerequisite maps
# to one package-manager command (Homebrew on macOS, apt-get or dnf on Linux, SDKMAN for the JDK), announced as
# `INSTALL <tool>: <command>` and run through `run` (dry-run aware). `sudo` only after the exact prompt answered `y`
# on a terminal; `--yes` never answers it. Never a curl-pipe-shell installer. Bash 3.2 compatible.

INSTALLED_COMMANDS=" "

# sudo_run COMMAND: elevated command, only after the prompt on a terminal; otherwise printed as `manual:`.
sudo_run() {
  local cmd="$1" answer
  if [ "$DRY_RUN" = 1 ]; then
    dry_run_line "sudo $cmd"
    return 0
  fi
  if ! is_interactive; then
    printf 'manual: sudo %s\n' "$cmd"
    return 0
  fi
  printf 'Run with elevated privileges? sudo %s [y/N]: ' "$cmd"
  read_answer answer
  case "$answer" in
    y | Y)
      # shellcheck disable=SC2086  # the command is a constant of this file, word splitting is intended
      run sudo $cmd ;;
    *) printf 'skipped: sudo %s\n' "$cmd" ;;
  esac
}

# execute_install TOOL COMMAND: announce, then run (or print the manual step).
execute_install() {
  local tool="$1" cmd="$2"
  case "$INSTALLED_COMMANDS" in *" $cmd "*) return 0 ;; esac
  INSTALLED_COMMANDS="$INSTALLED_COMMANDS$cmd "
  printf 'INSTALL %s: %s\n' "$tool" "$cmd"
  case "$cmd" in
    sudo\ *) sudo_run "${cmd#sudo }" ;;
    *)
      if [ "$DRY_RUN" = 1 ]; then
        dry_run_line "$cmd"
      elif is_interactive || [ "$YES" = 1 ]; then
        # shellcheck disable=SC2086  # constants of this file, word splitting is intended
        run $cmd || printf 'install failed: %s\n' "$cmd"
      else
        printf 'manual: %s\n' "$cmd"
      fi ;;
  esac
}

# pkg_install TOOL PACKAGE: the package-manager command of the detected OS.
pkg_install() {
  local tool="$1" pkg="$2"
  case "$PKG" in
    brew) execute_install "$tool" "brew install $pkg" ;;
    apt) execute_install "$tool" "sudo apt-get install -y $pkg" ;;
    dnf) execute_install "$tool" "sudo dnf install -y $pkg" ;;
    *) printf 'manual: install %s with your package manager\n' "$pkg" ;;
  esac
}

# install_for CHECK: the install (or printed decision) for one failing prerequisite.
install_for() {
  case "$1" in
    jdk) execute_install jdk "sdk env install" ;;
    engine)
      if [ "$OS" = macos ]; then
        printf 'decision: install Docker Desktop (https://docs.docker.com/desktop/) or run: brew install podman && podman machine init && podman machine start; the engine is not installed automatically\n'
      else
        pkg_install engine podman
      fi ;;
    compose)
      if [ "$OS" = macos ]; then pkg_install compose docker-compose; else pkg_install compose docker-compose-plugin; fi ;;
    memory | cpus | disk)
      printf 'manual: %s is an engine resource, adjust it as the fix line says\n' "$1" ;;
    node | npm)
      case "$PKG" in
        brew) pkg_install node "node@$NODE_PIN" ;;
        *) if command -v fnm >/dev/null 2>&1; then execute_install node "fnm install $NODE_PIN"; else pkg_install node "nodejs npm"; fi ;;
      esac ;;
    gitleaks)
      case "$PKG" in
        apt) printf 'manual: install gitleaks from https://github.com/gitleaks/gitleaks/releases (no Debian package)\n' ;;
        *) pkg_install gitleaks gitleaks ;;
      esac ;;
    curl | jq | openssl | git) pkg_install "$1" "$1" ;;
    *) ;;
  esac
}

# run_installs CHECKS...: installs for the failed prerequisites, then re-checks them once.
run_installs() {
  local name
  for name in "$@"; do install_for "$name"; done
}
