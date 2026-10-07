# Development environment: `scripts/dev-env.sh`

One command checks a developer machine, prepares the clone and runs the local platform with the storefront
(feature 005, user stories 3 and 5). This page summarises the command; the exact behaviour, line formats, exit codes
and prompts are the contract in
[`specs/005-storefront-dev-bootstrap/contracts/dev-env-cli.md`](../specs/005-storefront-dev-bootstrap/contracts/dev-env-cli.md),
from which the offline tests in `scripts/tests/test_dev_env_*.sh` are written.

```bash
scripts/dev-env.sh init --start      # check, configure the clone, build and start the platform, smoke-check it
scripts/dev-env.sh check             # the checks only
scripts/dev-env.sh status            # what is running, where, with how much engine headroom
scripts/dev-env.sh update            # after a pull: rebuild and restart only what changed, keep data
scripts/dev-env.sh reset             # back to a clean seeded platform (asks before deleting data)
scripts/dev-env.sh down              # stop everything, keep data (--volumes deletes it, after confirmation)
```

Run it from any directory; it finds the repository from its own location. It needs only Bash 3.2 or newer; the
tools it checks are the ones it then uses.

## Subcommands

| Subcommand | Does | Never does |
| --- | --- | --- |
| `init` (default) | runs the checks; with `--install` installs what is missing and re-checks; configures the clone (`.env`, secrets, free port, hooks, Podman settings); re-runs the `podman` and `port` checks; with `--start` builds and starts the platform, waits for health, runs the smoke checks and prints the addresses | install without `--install`, start without `--start`, overwrite a `.env` value that is set, print a secret, stop or remove anything |
| `check` | the 16 checks and the summary line | write a file or setting, install, start or stop a container |
| `status` | component table (healthy, starting, unhealthy, stopped), addresses, engine resources, then the checks | change or start anything |
| `update` | `up -d --build` of the platform project (Compose rebuilds only changed images), waits, smoke checks | `down`, remove a volume or an unchanged container, reseed, install or configure |
| `reset` | warns about running containers outside the platform, asks the data-loss confirmation, `down -v` of the platform project only, `up -d --build`, waits, runs the checks and smoke checks (`SEED=true` reseeds) | run without confirmation, delete `.env`, images or anything outside the platform project, run `prune` or any command without the project name |
| `down` | `down` of the platform project, prints `data kept`; `--volumes` also removes the data volumes after the confirmation and prints `data removed`; `OK platform already down` when nothing runs | remove volumes without `--volumes` and the confirmation, touch containers of other projects |

The platform project is the Compose project `ecommerce-platform` of `platform/compose/docker-compose.yml` with the
profiles `core` and `observability` (plus `ci` with `--runner-host`). Every Compose call names it (`-p
ecommerce-platform`); nothing is ever addressed by container name pattern, `--all` or a filter other than a read-only
`ps` by project label.

## Flags

| Flag | Applies to | Meaning |
| --- | --- | --- |
| `--install` | `init` | opt-in installs of missing tools through the package manager (Homebrew; apt-get or dnf; SDKMAN for the JDK), each announced as `INSTALL <tool>: <command>`; `sudo` only after the prompt `Run with elevated privileges? sudo <command> [y/N]:` answered `y` on a terminal |
| `--start` | `init` | start the platform and run the smoke checks |
| `--yes` | `init`, `reset`, `down --volumes` | non-interactive consent: outside-repository settings (`~/.testcontainers.properties`), non-`sudo` installs, the data-loss confirmation. It never answers a `sudo` prompt |
| `--dry-run` | all | print every mutation as `DRY-RUN: <command or action>` and perform none; read-only commands and checks still run |
| `--runner-host` | all but `reset` | CI host mode: adds the `ci` profile (Pact Broker) and the private registry of `platform/ci-runner/docker-compose.yml`, health-checks them (`broker`, `ci-reg` smoke lines) and ends with the pointer to "Register the runner" in `platform/ci-runner/README.md`. It never reads, accepts or prints a registration token and never registers the runner |
| `--verbose` | all | print each command run as `+ <command>` on stderr (never a secret) |
| `--volumes` | `down` | also remove the data volumes (confirmation required) |
| `-h`, `--help` | all | usage on stdout, exit 0 |

A flag the subcommand does not take, an unknown flag or subcommand, or two subcommands print the usage on stderr and
exit 2.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | success, everything already correct, or an abort the user chose (`aborted: nothing was changed`) |
| 2 | usage error; `reset` or `down --volumes` without a terminal and without `--yes` (`ERROR: <subcommand> deletes data; rerun with --yes`) |
| 3 | prerequisites missing: at least one check printed `FAIL` |
| 4 | the platform failed to start or failed its checks (a component not healthy within 600 s, a smoke check `FAIL`, `status` finding a core component not healthy) |

3 wins over 4; a `SKIP` never fails; `--dry-run` never exits 4.

## The checks

One line per check, `PASS`, `FAIL` or `SKIP`, with the found and expected values; every `FAIL` carries a
`fix (macos): ...` or `fix (linux): ...` line for the detected operating system; the last line is
`checks: <n> total, <p> PASS, <f> FAIL, <s> SKIP`.

| Check | Expected | Kind |
| --- | --- | --- |
| `jdk` | the major of `.java-version` (25) | prerequisite |
| `engine` | Docker or Podman reachable (Podman behind a Docker socket is detected as Podman) | prerequisite |
| `compose` | Compose v2 (`docker compose`, `docker-compose`, `podman compose`, or `COMPOSE_CMD`) | prerequisite |
| `memory`, `cpus`, `disk` | engine memory >= 10 GiB, CPUs >= 4, free disk >= 15 GiB | prerequisite |
| `podman` | `BUILDAH_FORMAT=docker` effective and `ryuk.disabled=true` in `~/.testcontainers.properties`; `SKIP (engine is docker)` otherwise; socket-shim and keyring-quota advice as `note:` lines | configuration (repaired by `init`) |
| `node`, `npm` | Node major of `frontend/.nvmrc` (24), npm >= 11 | prerequisite |
| `gitleaks`, `curl`, `jq` | installed | prerequisite |
| `openssl` | installed and Ed25519 capable | prerequisite |
| `git` | >= 2.9 (`core.hooksPath`) | prerequisite |
| `port` | `GATEWAY_PORT` free, or in use by this project's gateway | configuration (repaired by `init`) |
| `network` | the Docker registry answers within 3 s; `SKIP (no network)` otherwise, never `FAIL` (cached images work offline) | prerequisite |

A prerequisite `FAIL` stops `init` before it configures anything (exit 3), unless `--install` repairs it.

## What `init` configures

Each step prints `OK` (already correct, nothing changed), `CHANGE`, `SKIP` (with the reason) or `DRY-RUN`:

| Step | Effect |
| --- | --- |
| `env` | `platform/compose/.env` from `.env.example` when missing (mode 600); refuses, exit 3, when the file is not git-ignored |
| `secret` | generates `IDENTITY_SIGNING_KEY` (Ed25519, PKCS#8 DER, Base64) and `BROWSER_SESSION_KEY` (32 random bytes, Base64) when empty. The values flow from `openssl` into the file writer and are never printed; a non-empty value is never replaced |
| `port` | when `GATEWAY_PORT` is the example default, that port is taken by another process and a free one exists: records the next free port in `.env`. A port you chose is never changed |
| `hooks` | `git config core.hooksPath .githooks` (the secret-scanning pre-push hook) |
| `engine` | Podman only: records `BUILDAH_FORMAT=docker` in `.env` (the OCI default drops the image `HEALTHCHECK`) and exports it for its own Compose calls; prints the shell-profile hint, never edits a shell profile |
| `ryuk` | Podman only: `ryuk.disabled=true` in `~/.testcontainers.properties`, with consent (`--yes` or an interactive `y`) because it is outside the repository |

`.env` is rewritten through a temporary file in the same directory and moved into place, so an interrupted run never
leaves a half-written file. Secrets exist only in `platform/compose/.env` (git-ignored, verified): not in the output,
not in `--verbose` or `--dry-run` lines, not on a command line, not in any other file.

## Smoke checks and addresses

After `init --start`, `update` and `reset`, once every component is healthy:

| Line | Passes when |
| --- | --- |
| `entry` | `GET http://localhost:<GATEWAY_PORT>/api/v1/catalog/products` returns 200 |
| `isolation` | only `gateway`, `grafana` and `mailpit` (and `pact-broker` in runner-host mode) publish a host port |
| `storefront` | `GET /` returns 200, `text/html`, with a `Content-Security-Policy` header free of `unsafe-inline` |
| `broker`, `ci-reg` | runner-host mode: the Pact Broker heartbeat returns 200 and the private registry answers |

Then the addresses: storefront `http://localhost:<GATEWAY_PORT>/`, API, Grafana `:3000`, Mailpit `:8025` (and broker,
registry in runner-host mode).

## Without a terminal

Non-interactive means stdin is not a terminal or `DEV_ENV_NON_INTERACTIVE=1`: no prompts, the safe default for every
choice, and the choice reported on stdout. `reset` and `down --volumes` refuse without `--yes` (exit 2); `--install`
prints `manual: <command>` for anything it would need consent for; the `ryuk` step prints `SKIP ryuk ... consent not
given (rerun with --yes)`; a free-port proposal is applied and reported (repository-local, no data loss).

## Environment variables

| Variable | Effect |
| --- | --- |
| `GATEWAY_PORT` | host port of the gateway and storefront: process environment, then `platform/compose/.env`, then 8080 |
| `COMPOSE_CMD` | Compose provider command (`docker compose`, `docker-compose`, `podman compose`); overrides detection |
| `CONTAINER_ENGINE` | `docker` or `podman`; overrides detection |
| `DEV_ENV_NON_INTERACTIVE` | `1` behaves as having no terminal; `0` forces the prompts to be read from stdin (used by the tests) |
| `NO_COLOR` | disables colour (colour is also off when stdout is not a terminal) |
| `DEV_ENV_WAIT_SECONDS`, `DEV_ENV_POLL_SECONDS` | health-wait budget (600) and poll interval (3); the tests shorten them |

## Tests and lint

`scripts/tests/run-all.sh` runs every `scripts/tests/test_*.sh` offline, without an engine or the network: the
binaries the script calls (`docker`, `podman`, `docker-compose`, `java`, `node`, `npm`, `brew`, `apt-get`, `dnf`,
`sudo`, `gitleaks`, `curl`, `jq`, `openssl`, `git`, `sdk`, `df`, `uname`) are stubs in `scripts/tests/stubs/`, driven
by `STUB_*` variables and logging every call, so the tests can prove what a dry run did not do and that no command
ever carried a secret. `scripts/lint.sh` runs `shellcheck -x` over the same file list as the platform workflow. Both
are part of `./gradlew -q verify` (`scriptsCheck`, see [build.md](build.md)) and silent on success.
