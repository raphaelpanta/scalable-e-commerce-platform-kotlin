<!-- rtk-instructions v2 -->
# Command output

Command output here is condensed to save tokens, keeping every signal and
dropping costly noise. Treat it as the complete result: run commands
normally, and batch related commands into one call to avoid extra turns.
Truncated results state their recovery path in their own output. Re-run a
command as `rtk proxy <cmd>` only when its result is unusable: empty when
output was clearly expected, contradicting its exit code, or garbled.
<!-- /rtk-instructions -->

# Build

Run everything from the repository root (details in `docs/build.md`):

- `./gradlew -q verify`: the whole gate (toolchain, version literals, build-logic tests, every module's
  ktlint, detekt, test layers, architecture rules and Pitest); silent on success.
- One layer of one service module:
  `./gradlew -q :services:<name>:infrastructure:test`, `:integrationTest`, `:contractTest`, `:acceptanceTest`
  (unit tests of `domain`/`application`: `./gradlew -q :services:<name>:domain:test`).
- Pacts: `contractTest` runs consumers and writes `build/pacts` at the root; `contractVerify` runs the classes tagged
  `provider` after every consumer (`./gradlew -q contractTest contractVerify` for the whole repository).
- New service: `./gradlew newService -Pname=<context>` (then `./gradlew -q verify`).
- Versions live only in `gradle/libs.versions.toml`.
