#!/usr/bin/env bash
# Service pipeline (feature 004, T116): converts the output of
#   ./gradlew -q :services:<svc>:infrastructure:dependencies --configuration runtimeClasspath
# into a Gradle lockfile (one `group:artifact:version=runtimeClasspath` line per resolved external module, sorted
# and unique, then `empty=`) that osv-scanner reads as `gradle.lockfile`. The build does not use dependency locking,
# so this is how the resolved versions reach the scanner. Project dependencies, constraints (c) and unresolved
# entries are skipped; a conflict resolution `a:b:1.0 -> 2.0` yields version 2.0, a substitution
# `a:b:1.0 -> c:d:3.0` yields c:d:3.0.
# Usage: gradle-deps-to-lockfile.sh [FILE]   (stdin when FILE is omitted or -); the lockfile goes to stdout.
set -uo pipefail

usage() { echo "Usage: gradle-deps-to-lockfile.sh [FILE]" >&2; exit 64; }
[ $# -le 1 ] || usage
input="${1:--}"
[ "$input" = "-" ] || [ -r "$input" ] || { echo "cannot read $input" >&2; exit 66; }

echo "# Generated from the Gradle dependency tree by .github/scripts/gradle-deps-to-lockfile.sh"
{
  if [ "$input" = "-" ]; then cat; else cat "$input"; fi
} | awk '
  # tree lines: [| ]*(+|\)--- <coordinate> [-> <resolution>] [markers]
  /^[| ]*[+\\]--- / {
    line = $0
    sub(/^[| ]*[+\\]--- /, "", line)
    if (line ~ /^project /) next
    if (line ~ /\(c\)/) next                      # constraint, not a resolved dependency
    sub(/ \((\*|n)\)$/, "", line)
    sub(/ FAILED$/, "", line)
    n = split(line, side, / -> /)
    left = side[1]
    split(left, lp, ":")
    group = lp[1]; artifact = lp[2]
    version = ""
    if (n >= 2) {
      right = side[2]
      if (right ~ /:/) {                          # substitution by another module
        split(right, rp, ":")
        group = rp[1]; artifact = rp[2]; version = rp[3]
      } else {
        version = right
      }
    } else {
      version = lp[3]
    }
    gsub(/[{} ]/, "", version)
    sub(/^(strictly|require|prefer)/, "", version)
    if (group == "" || artifact == "" || version == "") next
    print group ":" artifact ":" version "=runtimeClasspath"
  }
' | sort -u
echo "empty="
