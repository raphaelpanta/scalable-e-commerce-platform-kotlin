#!/usr/bin/env bash
# T116 (feature 004): .github/scripts/gradle-deps-to-lockfile.sh turns `gradle dependencies --configuration
# runtimeClasspath` output into the gradle.lockfile that osv-scanner reads.
. "$(dirname "$0")/lib.sh"
T_OUT="$(mktemp -d "${TMPDIR:-/tmp}/lockfiletest.XXXXXX")"; T_TMPS="$T_OUT"
cat >"$T_OUT/deps.txt" <<'DEPS'

------------------------------------------------------------
Project ':services:cart:infrastructure'
------------------------------------------------------------

runtimeClasspath - Runtime classpath of source set 'main'.
+--- project :services:cart:domain
|    \--- org.jetbrains.kotlin:kotlin-stdlib:2.4.0
+--- project :services:cart:application
+--- org.springframework.boot:spring-boot-starter-webflux -> 4.1.0
|    +--- org.springframework.boot:spring-boot-starter:4.1.0
|    |    +--- org.springframework.boot:spring-boot:4.1.0 (*)
|    |    \--- org.springframework.boot:spring-boot-starter-logging:4.1.0 (c)
|    \--- io.projectreactor.netty:reactor-netty-http:1.3.0 -> 1.3.1
+--- org.jetbrains.kotlin:kotlin-reflect:{strictly 2.4.0} -> 2.4.0
+--- com.example:old-name:1.0 -> com.example:new-name:2.0
\--- org.postgresql:postgresql -> 42.7.8 (n)

(c) - A dependency constraint, not a dependency.
(*) - Indicates repeated occurrences of a transitive dependency subtree.
DEPS

run_script gradle-deps-to-lockfile.sh "$T_OUT/deps.txt"
assert_exit 0
for coordinate in \
  'org.jetbrains.kotlin:kotlin-stdlib:2.4.0=runtimeClasspath' \
  'org.springframework.boot:spring-boot-starter-webflux:4.1.0=runtimeClasspath' \
  'org.springframework.boot:spring-boot:4.1.0=runtimeClasspath' \
  'io.projectreactor.netty:reactor-netty-http:1.3.1=runtimeClasspath' \
  'org.jetbrains.kotlin:kotlin-reflect:2.4.0=runtimeClasspath' \
  'com.example:new-name:2.0=runtimeClasspath' \
  'org.postgresql:postgresql:42.7.8=runtimeClasspath'; do
  assert_contains stdout "$coordinate"
done
assert_not_contains stdout 'project :'
assert_not_contains stdout 'spring-boot-starter-logging'   # constraint
assert_not_contains stdout 'old-name'                      # substituted
assert_contains stdout 'empty='
[ "$(grep -c '=runtimeClasspath' "$T_OUT/stdout")" = 8 ] || _t_fail "expected 8 resolved modules, got $(grep -c '=runtimeClasspath' "$T_OUT/stdout")"

# stdin and a missing file
"$SCRIPTS_DIR/gradle-deps-to-lockfile.sh" <"$T_OUT/deps.txt" >"$T_OUT/stdin.out" 2>/dev/null
cmp -s "$T_OUT/stdin.out" "$T_OUT/stdout" || _t_fail "stdin and file input must give the same lockfile"
run_script gradle-deps-to-lockfile.sh "$T_OUT/does-not-exist"; assert_exit 66
test_done
