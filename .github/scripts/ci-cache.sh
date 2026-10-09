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
#
# No-op (exit 0) without CI_CACHE_DIR, e.g. on a GitHub-hosted runner or a laptop.
# Usage: ci-cache.sh gradle   (from the repository root, after the checkout)
set -euo pipefail

usage() { echo "Usage: ci-cache.sh gradle" >&2; exit 64; }
[ $# -eq 1 ] || usage
[ -n "${CI_CACHE_DIR:-}" ] || exit 0

case "$1" in
  gradle)
    key="$(pwd -P | cksum | cut -d' ' -f1)"
    target="$CI_CACHE_DIR/gradle-configuration-cache/$key"
    mkdir -p "$target" .gradle
    rm -rf .gradle/configuration-cache
    ln -s "$target" .gradle/configuration-cache
    ;;
  *) usage ;;
esac
