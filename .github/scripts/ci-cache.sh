#!/usr/bin/env bash
# Runner-side caches of the CI jobs (platform/ci-runner/README.md, "Caches"). The self-hosted runners keep their tool
# caches in CI_CACHE_DIR, a volume that outlives the runner containers (Gradle user home with the dependencies, the
# wrapper and the local build cache; npm cache; Playwright browsers). What lives inside the checkout is lost with every
# job, because actions/checkout cleans the workspace; this script links such directories into CI_CACHE_DIR instead:
#
#   gradle   <workspace>/.gradle/configuration-cache. A hit skips the configuration phase of the ~30 projects (tens of
#            seconds per Gradle job); Gradle checks every entry against the build scripts, properties and
#            environment it was computed from, so a stale entry is never used. Keyed by the workspace path, which
#            Gradle requires to be unchanged.
#   gitleaks gitleaks GITLEAKS_VERSION on the job's PATH (GITHUB_PATH), which the pre-push hook and its tests in
#            scripts/tests need (developers install it with Homebrew). Downloaded once from the gitleaks release,
#            checked against the pinned SHA-256 and kept in CI_CACHE_DIR/tools (RUNNER_TEMP without it).
#   stryker restore|save
#            frontend/build/stryker-incremental.json of THIS commit (GITHUB_SHA): the storefront gate and the pr-gate
#            mutation job both run Stryker on the same pull-request merge commit, about 16 minutes each. `save` keeps the
#            first run's result, `restore` hands it to the second, which then re-runs nothing it already ran. A new
#            commit always starts without a file, so CI still mutates every commit in full. Kept 7 days.
#
# `gradle` and `stryker` are no-ops (exit 0) without CI_CACHE_DIR, e.g. on a GitHub-hosted runner or a laptop.
# Usage: ci-cache.sh gradle|gitleaks|stryker restore|stryker save   (from the repository root, after the checkout)
set -euo pipefail

GITLEAKS_VERSION=8.30.1
GITLEAKS_SHA256_LINUX_ARM64=e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080
GITLEAKS_SHA256_LINUX_X64=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb

usage() { echo "Usage: ci-cache.sh gradle|gitleaks|stryker restore|stryker save" >&2; exit 64; }
[ $# -ge 1 ] && [ $# -le 2 ] || usage
[ $# -eq 1 ] || [ "$1" = stryker ] || usage

case "$1" in
  gitleaks)
    case "$(uname -s)-$(uname -m)" in
      Linux-aarch64 | Linux-arm64) arch=arm64; sum="$GITLEAKS_SHA256_LINUX_ARM64" ;;
      Linux-x86_64) arch=x64; sum="$GITLEAKS_SHA256_LINUX_X64" ;;
      *) echo "ci-cache.sh: no pinned gitleaks for $(uname -s) $(uname -m)" >&2; exit 1 ;;
    esac
    dir="${CI_CACHE_DIR:-${RUNNER_TEMP:?}}/tools/gitleaks-$GITLEAKS_VERSION"
    if [ ! -x "$dir/gitleaks" ]; then
      tmp="$(mktemp -d)"
      trap 'rm -rf "$tmp"' EXIT
      archive="gitleaks_${GITLEAKS_VERSION}_linux_${arch}.tar.gz"
      curl -fsSL --retry 3 -o "$tmp/$archive" \
        "https://github.com/gitleaks/gitleaks/releases/download/v$GITLEAKS_VERSION/$archive"
      echo "$sum  $tmp/$archive" | sha256sum -c --quiet -
      tar -xzf "$tmp/$archive" -C "$tmp" gitleaks
      mkdir -p "$dir"
      mv "$tmp/gitleaks" "$dir/gitleaks"
    fi
    if [ -n "${GITHUB_PATH:-}" ]; then echo "$dir" >>"$GITHUB_PATH"; else echo "$dir"; fi
    ;;
  gradle)
    [ -n "${CI_CACHE_DIR:-}" ] || exit 0
    key="$(pwd -P | cksum | cut -d' ' -f1)"
    target="$CI_CACHE_DIR/gradle-configuration-cache/$key"
    mkdir -p "$target" .gradle
    rm -rf .gradle/configuration-cache
    ln -s "$target" .gradle/configuration-cache
    ;;
  stryker)
    case "${2:-}" in restore | save) ;; *) usage ;; esac
    [ -n "${CI_CACHE_DIR:-}" ] && [ -n "${GITHUB_SHA:-}" ] || exit 0
    dir="$CI_CACHE_DIR/stryker"
    local_file=frontend/build/stryker-incremental.json
    mkdir -p "$dir"
    case "${2:-}" in
      restore)
        if [ -s "$dir/$GITHUB_SHA.json" ]; then
          mkdir -p "${local_file%/*}"
          cp "$dir/$GITHUB_SHA.json" "$local_file"
          echo "Stryker: reusing the results of an earlier run of $GITHUB_SHA"
        fi
        ;;
      save)
        if [ -s "$local_file" ]; then
          cp "$local_file" "$dir/$GITHUB_SHA.json.tmp"
          mv "$dir/$GITHUB_SHA.json.tmp" "$dir/$GITHUB_SHA.json"
        fi
        find "$dir" -name '*.json' -mtime +7 -delete 2>/dev/null || true
        ;;
      *) usage ;;
    esac
    ;;
  *) usage ;;
esac
