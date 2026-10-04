# Command Contract: `scripts/dev-env.sh`

Feature: 005-storefront-dev-bootstrap. Covers FR-020 to FR-030 and FR-033, SC-001, SC-002, SC-008, SC-010 and
[research.md section 8 and 9](../research.md). The contract is precise enough to write `scripts/tests/test_dev_env_*.sh` from:
tests run offline with stubbed `docker`, `podman`, `java`, `node`, `npm`, `brew`, `gitleaks`, `curl`, `jq`, `openssl` and
`git` binaries first on `PATH` (`scripts/tests/stubs/`), a temporary `HOME`, and a temporary copy of `platform/compose/`.

## Synopsis

```
scripts/dev-env.sh [SUBCOMMAND] [FLAGS]
SUBCOMMAND := init | check | status | update | reset | down        (default: init)
FLAGS      := --install --start --yes --dry-run --runner-host --verbose --volumes -h|--help
```

Run from any directory; the script resolves the repository root from its own location. Output goes to stdout; diagnostics
(`ERROR: ...`, usage) go to stderr. Without a terminal or with `NO_COLOR` set, output has no ANSI colour (tests rely on this).

## Subcommands

| Subcommand | Does | Never does |
| --- | --- | --- |
| `init` (default) | 1. runs all checks (below); 2. if a prerequisite check failed: with `--install` installs what is missing (announced, see Installs) and re-checks, otherwise stops with exit 3 and changes nothing; 3. configures the clone (Configuration steps); 4. runs the two configuration checks `podman` and `port`; 5. with `--start`: starts the platform, waits for health, runs the smoke checks, prints the addresses | install without `--install`; start containers without `--start`; overwrite a `.env` value that is already set; print or commit a secret; stop or remove anything; touch containers of other projects |
| `check` | the checks only, in the order of the check table, then the summary line | write any file or setting; install; start or stop a container; contact the platform |
| `status` | table of platform components (service, health), the public addresses, engine resources (memory, CPUs, free disk), then the checks and their summary | change anything; start anything |
| `update` | `up -d --build` of the platform project: rebuilds and restarts only components whose build inputs changed (Compose build cache), keeps all volumes, waits for health, runs the smoke checks | `down`; remove a volume or a container that is unchanged; reseed; run installs or configuration |
| `reset` | asks the data-loss confirmation, then `down -v` of the platform project only, `up -d --build`, waits for health (the `SEED=true` start-up reseeds), runs the checks and smoke checks | run without confirmation; delete `.env`, images, build cache or anything outside the platform project; run `docker system prune` or any command without the project name |
| `down` | `down` of the platform project, keeping volumes; prints `data kept` | remove volumes unless `--volumes` and the confirmation; stop containers of other projects |

The platform project is the Compose project `ecommerce-platform` of `platform/compose/docker-compose.yml`, profiles `core` and
`observability` (plus `ci` with `--runner-host`). Every Compose call names it explicitly; nothing is ever addressed by container
name pattern or `--all`.

### Flag applicability

| Flag | init | check | status | update | reset | down | Meaning |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `--install` | yes | no | no | no | no | no | opt-in installs of missing tools, see Installs |
| `--start` | yes | no | no | no | no | no | start the platform and run smoke checks |
| `--yes` | yes | no | no | no | yes | yes (with `--volumes`) | non-interactive consent: outside-repository settings, non-`sudo` installs, data-loss confirmation. It never answers a `sudo` prompt |
| `--dry-run` | yes | yes | yes | yes | yes | yes | print every mutation as `DRY-RUN: <command or action>` and perform none; read-only commands and checks still run |
| `--runner-host` | yes | yes | yes | yes | no | yes | adds the `ci` profile (Pact Broker) and the private registry of `platform/ci-runner/docker-compose.yml`, starts and health-checks them, and ends by referring to the "Register the runner" section of `platform/ci-runner/README.md`. Never read, accept or print a registration token (`ACCESS_TOKEN`) and never registers the runner |
| `--verbose` | all | all | all | all | all | all | print each command run as `+ <command>` to stderr; never secrets |
| `--volumes` | no | no | no | no | no | yes | `down` also removes the data volumes (confirmation required) |
| `-h`, `--help` | all | all | all | all | all | all | print usage to stdout, exit 0 |

A flag not applicable to the subcommand, an unknown flag or subcommand, or more than one subcommand: usage text on stderr, exit 2.

## Exit codes

| Code | Meaning | Examples |
| --- | --- | --- |
| 0 | success, or an abort the user chose, or everything already correct | all checks pass; `down` when nothing runs; confirmation answered other than `yes` ("aborted: nothing was changed") |
| 2 | usage error | unknown subcommand or flag; flag not applicable; `reset` or `down --volumes` without a terminal and without `--yes` |
| 3 | prerequisites missing | at least one check printed `FAIL` (prerequisite or configuration check) |
| 4 | platform failed to start or failed its checks | a component not healthy within the timeout; a smoke check printed `FAIL`; `status` found a core component not healthy while all prerequisite checks pass |

When both apply, 3 wins over 4. A `SKIP` never causes a non-zero exit. `--dry-run` exits like the real run would for the
read-only parts (3 on a missing prerequisite); it never exits 4 because it starts nothing.

## Environment variables honoured

| Variable | Effect |
| --- | --- |
| `GATEWAY_PORT` | Host port of the gateway/storefront. Precedence: process environment, then `platform/compose/.env`, then `8080` |
| `COMPOSE_CMD` | Compose provider command, word-split, for example `docker compose`, `docker-compose`, `podman compose`; overrides detection |
| `CONTAINER_ENGINE` | `docker` or `podman`; overrides detection (otherwise the first of `docker`, `podman` that answers `info`) |
| `DEV_ENV_NON_INTERACTIVE` | `1` behaves as having no terminal: no prompts, see Non-interactive behaviour |
| `NO_COLOR` | any non-empty value disables ANSI colour (colour is also off when stdout is not a terminal) |

`HOME` locates `~/.testcontainers.properties`. No other variable changes behaviour. Values of `*KEY*`, `*PASSWORD*` and `*TOKEN*`
variables are never printed.

## Check lines

One line per check, printed in the order of the table below, with `printf '%-4s  %-10s %-12s   %s\n'`
(status, name, found, expected), no colour in the contract text:

```
PASS  jdk        found 25.0.4   expected 25
FAIL  jq         found none     expected installed
      fix (macos): brew install jq
```

| Element | Rule |
| --- | --- |
| status | `PASS`, `FAIL` or `SKIP` |
| name | lower-case id from the table, at most 10 characters |
| found | `found <value>`; `found none` when absent; longer values simply widen the column |
| expected | `expected <value>` as in the table |
| fix line | printed under every `FAIL`, indented by 6 spaces: `fix (macos): <command>` or `fix (linux): <command>` for the detected operating system only; several alternatives use a following `fix (...)` line each. A fix line is text; it is never executed except through `--install` |
| `SKIP` | `SKIP  <name>      (<reason>)`, reasons: `(no network)` for `network`, `(engine is docker)` for `podman` |
| summary | last line of `check`, `status` and of the check phase of `init`: `checks: <n> total, <p> PASS, <f> FAIL, <s> SKIP` |

### Checks (16)

Kind P = prerequisite (evaluated first in `init`; a FAIL stops `init` before configuration unless `--install` fixes it);
kind C = configuration (evaluated after the configuration step in `init`, so that `init` can repair them; in `check` they are evaluated as they are).

| # | Name | Kind | Expected | PASS when | Fix (macos / linux) |
| --- | --- | --- | --- | --- | --- |
| 1 | `jdk` | P | `25` (major of `.java-version`; `.sdkmanrc` pins `25.0.4-tem`) | `java -version` major equals the pin; found shows the full version | `sdk env install` / same |
| 2 | `engine` | P | `docker or podman reachable` | `<engine> info` succeeds; found shows engine and version | print the decision, never install a cask: `install Docker Desktop, or brew install podman && podman machine init && podman machine start` / `install podman or docker engine, start the service` |
| 3 | `compose` | P | `v2` | the Compose provider reports major version 2 or higher (`docker compose version`, `docker-compose version`, or `COMPOSE_CMD`) | `brew install docker-compose` or enable it in Docker Desktop / `sudo apt-get install docker-compose-plugin` or `sudo dnf install docker-compose-plugin` (or `podman-compose` as the provider) |
| 4 | `memory` | P | `>= 10 GiB` | engine memory (VM or host) at least 10 GiB | `podman machine set --memory 10240` or Docker Desktop resources / free memory or add swap-free RAM |
| 5 | `cpus` | P | `>= 4` | engine CPUs at least 4 | `podman machine set --cpus 4` or Docker Desktop resources / n/a (host CPUs) |
| 6 | `disk` | P | `>= 15 GiB free` | the lower of free space on the repository filesystem and on the engine storage is at least 15 GiB | `free disk space; podman system prune or docker image prune (images of other projects are yours to judge)` / same |
| 7 | `podman` | C | `BUILDAH_FORMAT=docker, ryuk disabled` | engine is Podman and `BUILDAH_FORMAT=docker` is effective (environment or `.env`) and `~/.testcontainers.properties` contains `ryuk.disabled=true`; `SKIP (engine is docker)` otherwise; the socket shim and keyring-quota advice are printed as `note:` lines, not as failures | `run: scripts/dev-env.sh init` (sets both) / same |
| 8 | `node` | P | `24` (major of `frontend/.nvmrc`) | `node --version` major equals it | `brew install node@24` / `fnm install 24` or the distro package |
| 9 | `npm` | P | `>= 11` (the version bundled with Node 24) | `npm --version` major at least 11 | reinstall Node 24 (`brew install node@24`) / same |
| 10 | `gitleaks` | P | `installed` | `gitleaks version` runs | `brew install gitleaks` / the release binary of gitleaks or the distro package |
| 11 | `curl` | P | `installed` | `curl --version` runs | `brew install curl` / `sudo apt-get install curl` or `sudo dnf install curl` |
| 12 | `jq` | P | `installed` | `jq --version` runs | `brew install jq` / `sudo apt-get install jq` or `sudo dnf install jq` |
| 13 | `openssl` | P | `ed25519 capable` | `openssl version` runs and `openssl genpkey -algorithm ed25519` succeeds | `brew install openssl` / `sudo apt-get install openssl` or `sudo dnf install openssl` |
| 14 | `git` | P | `>= 2.9` | `git --version` at least 2.9 (needed for `core.hooksPath`) | `brew install git` / `sudo apt-get install git` or `sudo dnf install git` |
| 15 | `port` | C | `<GATEWAY_PORT> free` | a TCP connection to `127.0.0.1:<GATEWAY_PORT>` is refused, or the listener is this project's own gateway container; found is `<port> free` or `<port> in use` | `init proposes a free port, or set GATEWAY_PORT in platform/compose/.env` / same |
| 16 | `network` | P | `registry reachable` | `curl` reaches `https://registry-1.docker.io/v2/` (any HTTP status) within 3 s; otherwise `SKIP (no network)`, never `FAIL`, because cached images work offline | n/a |

Whole `check` completes in under 30 seconds and a repeated `init` without `--start` under 10 seconds (SC-002): every probe has a
timeout of at most 3 seconds.

## Configuration steps (`init`)

Each step prints one line `printf '%-7s %-10s %s\n'`: status (`OK` already correct, `CHANGE` performed, `SKIP` not done with the
reason, `DRY-RUN` not done because of `--dry-run`), id, text. Nothing is changed when the status is `OK`.

| Id | Step | Condition | Outside the repository? |
| --- | --- | --- | --- |
| `env` | create `platform/compose/.env` from `.env.example` (mode 600) | file missing | no |
| `secret` | generate `IDENTITY_SIGNING_KEY` (Ed25519 PKCS#8 DER, Base64) and `BROWSER_SESSION_KEY` (32 random bytes, Base64) | the key is absent or empty; a non-empty value is never replaced | no |
| `port` | record a free `GATEWAY_PORT` (next free port after the default) | the value in `.env` equals the example default, that port is in use by a process that is not this project's gateway, and a free one exists. A port the developer chose is never changed (the `port` check then fails with a fix line) | no |
| `hooks` | `git config core.hooksPath .githooks` | the setting differs | no (repository-local `.git/config`) |
| `engine` | Podman only: record `BUILDAH_FORMAT=docker` in `.env` when no value is set and export it for the script's own Compose calls; print the shell-profile hint; never edit shell profiles | engine is Podman and no value is set | no |
| `ryuk` | Podman only: set `ryuk.disabled=true` in `~/.testcontainers.properties` (create or add the line, keep other lines) | engine is Podman and the line is absent | **yes**: needs consent (`--yes` or an interactive `y`), otherwise `SKIP ryuk ... consent not given` |

`.env` keys the script manages (all others keep the example values and are never modified):

| Key | Written when | Secret |
| --- | --- | --- |
| `IDENTITY_SIGNING_KEY` | absent or empty | yes |
| `BROWSER_SESSION_KEY` | absent or empty | yes |
| `GATEWAY_PORT` | default busy, see `port` step | no |
| `BUILDAH_FORMAT` | Podman and absent | no |

The step order is `env`, `secret`, `port`, `hooks`, `engine`, `ryuk`. `.env` is rewritten through a temporary file in the same
directory (mode 600, removed afterwards) and only when a key actually changes. The script verifies `git check-ignore -q
platform/compose/.env` and fails the `env` step (exit 3) when the file is not git-ignored.

## Installs (`--install`, `init` only)

| Rule | Behaviour |
| --- | --- |
| Opt-in | nothing is installed without `--install`; missing tools are only reported with fix lines |
| Announce | before each installation a line `INSTALL <tool>: <exact command>` is printed |
| Package manager | macOS: Homebrew (`brew install podman gitleaks jq node@24 openssl git curl`; never `brew install --cask docker`: the Docker Desktop or Podman decision is printed, not made); Linux: `apt-get` or `dnf` (`podman`, `podman-compose` or `docker-compose-plugin`, `jq`, `curl`, `git`, `openssl`; `gitleaks` from its release only when the distro has no package, and then as a printed manual step); JDK through SDKMAN (`sdk env install`, reading `.sdkmanrc`); Node through the distro or `fnm` |
| Elevation | a command that needs `sudo` is run only after the prompt `Run with elevated privileges? sudo <command> [y/N]: ` answered `y` on a terminal. `--yes` never answers it; without a terminal it is printed as `manual: sudo <command>` and not run |
| Dry run | every install goes through the `run` wrapper: printed as `DRY-RUN: <command>` |
| After | the failing checks are re-run once; remaining `FAIL`s end `init` with exit 3 |
| Never | curl-pipe-shell installers, downloading and executing anything outside a package manager |

## Smoke checks and addresses

After a start (`init --start`, `update`, `reset`), after `wait_healthy` for every component (timeout 600 s per start; `core` and
`observability` services must report healthy), these lines use the check-line format and any `FAIL` ends with exit 4:

| Name | Expected | PASS when |
| --- | --- | --- |
| `entry` | `200 from gateway` | `GET http://localhost:<GATEWAY_PORT>/api/v1/catalog/products` returns 200 |
| `isolation` | `only gateway published` | `compose ps` publishes ports only for `gateway`, `grafana`, `mailpit` (and `pact-broker` with `--runner-host`) |
| `storefront` | `GET / serves the app` | `GET /` returns 200 with `text/html` and a `Content-Security-Policy` header without `unsafe-inline` |

With `--runner-host` two more: `broker` (`GET http://localhost:9292/diagnostic/status/heartbeat` returns 200) and `ci-reg` (the private
registry answers). Then the script prints the addresses and, in runner-host mode, the pointer to the registration procedure:

```
addresses
  storefront  http://localhost:<GATEWAY_PORT>/
  api         http://localhost:<GATEWAY_PORT>/api/v1/catalog/products
  grafana     http://localhost:3000
  mailpit     http://localhost:8025
runner host: register the runner as described in platform/ci-runner/README.md ("Register the runner")
```

## Confirmation prompts

Data loss (`reset`, `down --volumes`), printed on stdout and read from a terminal, exact text:

```
This will DELETE all data of the platform (compose project 'ecommerce-platform'): databases, Kafka, Loki, Tempo, Prometheus, Grafana and Pact Broker volumes. Containers, images and volumes of other projects are not touched.
Type 'yes' to continue, anything else aborts [yes/N]: 
```

Only the exact answer `yes` continues. Anything else prints `aborted: nothing was changed` and exits 0. `--yes` skips the prompt and
prints `confirmed by --yes: deleting platform data`.

## Non-interactive behaviour

Non-interactive means stdin is not a terminal or `DEV_ENV_NON_INTERACTIVE=1`.

| Situation | Behaviour |
| --- | --- |
| `reset`, `down --volumes` without `--yes` | refuses: `ERROR: <subcommand> deletes data; rerun with --yes`, exit 2, nothing changed |
| `init --install` without `--yes` | nothing is installed; each command is printed as `manual: <command>` |
| `init --install --yes` | non-`sudo` installs run; `sudo` ones are printed as `manual:` |
| outside-repository setting (`ryuk`) without `--yes` | `SKIP ryuk ... consent not given (rerun with --yes)` |
| free-port proposal | applied and reported with a `CHANGE port` line (repository-local, no data loss) |
| any choice taken or skipped | reported on stdout as a line; no silent decisions |

## Idempotency and dry-run guarantees

| Guarantee | Test shape |
| --- | --- |
| A second `init` (and `check`, `status`) on a configured clone prints no `CHANGE` line, exits like the first run's final state, and does not modify `.env` (byte-identical, modification time unchanged) or `~/.testcontainers.properties` | run twice with stubs; compare checksums and `CHANGE` count |
| `init --start`, `update` and `down` rerun on an already correct state change nothing (`up -d` recreates no unchanged container; `down` on a stopped platform prints `OK platform already down`, exit 0) | stub `compose` records calls |
| `--dry-run` writes no file, runs no `git config` write, no installer, no mutating Compose command (`up`, `down`, `build`, `pull`), contacts no network write endpoint; each skipped mutation prints one `DRY-RUN: <what>` line; secrets generation prints `DRY-RUN: generate BROWSER_SESSION_KEY in platform/compose/.env` without a value; a run after a dry run still finds everything to do | stubs log mutations; assert empty log and unchanged tree |
| Only platform containers are ever touched: every Compose call carries `-p ecommerce-platform` (or runs from `platform/compose/` whose `name:` is that project); no `rm`, `stop`, `kill` or `prune` of an engine object by name or filter | stub records and the test asserts the argument pattern |
| Behaviour with no terminal is safe (table above) | run with stdin from `/dev/null` |

## Secrets rule

| Rule | Detail |
| --- | --- |
| Never echoed | no value of `IDENTITY_SIGNING_KEY`, `BROWSER_SESSION_KEY`, or any `*KEY*`, `*PASSWORD*`, `*TOKEN*` entry of `.env` appears in stdout, stderr, `--verbose`, `--dry-run` or `status` output; only the key name and `generated` or `already set` |
| Never outside `.env` | secrets are written only to `platform/compose/.env`; no other file, no shell profile, no temporary file outside that directory, no command-line argument (the generator pipes into the file writer), no engine label or environment dump |
| Never overwritten | a non-empty value is never replaced, rotated or re-generated |
| Never committed | `.env` is git-ignored (verified); the script never runs `git add`, `git commit` or `git config` for anything except `core.hooksPath` |
| Verified | SC-008: `gitleaks` over a recorded transcript of `init`, `init --dry-run`, `status` and `reset --yes` (with stubs and generated keys) finds nothing, in `scripts/tests/test_dev_env_secrets.sh` |
