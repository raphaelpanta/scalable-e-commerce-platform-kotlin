# CI runner stack (feature 004)

Everything the per-service and platform pipelines need on the CI host (research.md sections 4 and 5, tasks T108 and
T109): an **ephemeral GitHub Actions runner**, a **private image registry** (`registry:3`, basic auth, TLS) and the
**Pact Broker** with its PostgreSQL. Workflows are documented in [docs/ci-cd.md](../../docs/ci-cd.md); this page covers
the host.

| Service | Image | Published on the host |
| --- | --- | --- |
| `runner` | `myoung34/github-runner:2.337.0-ubuntu-noble` (tracks `actions/runner` 2.337.0; includes Docker CLI, Compose, git, jq) | nothing (host network) |
| `registry` | `registry:3.1.2`, htpasswd (bcrypt) auth, TLS | `${REGISTRY_BIND_ADDRESS}:${REGISTRY_PORT}` = `127.0.0.1:5443` |
| `pact-broker`, `pact-broker-db` | `pactfoundation/pact-broker:3.0.0-pactbroker2.121.2`, `postgres:18-alpine` (same as the `ci` profile of `platform/compose`) | `${PACT_BROKER_BIND_ADDRESS}:${PACT_BROKER_PORT}` = `127.0.0.1:9292` |

Images are pinned by tag (the runner and the registry also by digest). Restart policies: `unless-stopped` for the
registry, the broker and its database; `always` for the runner (see "Ephemeral behaviour"). Volumes: `registry-data`,
`pact-broker-db-data`, and the bind-mounted `data/` (certificate, htpasswd; git-ignored) and `RUNNER_WORKDIR`.

## Host requirements

A dedicated Linux machine or VM (see the warning below) with Docker Engine and the Compose plugin, at least 4 CPU cores
and 16 GB of memory (Gradle, Testcontainers, the platform workflow's stack of about 8 GB), outbound access to GitHub,
Maven Central, the Gradle Plugin Portal, `api.osv.dev` and the registries of the tool images, and `gh` (logged in) if you
use `scripts/run-ephemeral.sh`.

## Setup

```bash
cd platform/ci-runner
cp .env.example .env && $EDITOR .env        # REPO_URL, RUNNER_WORKDIR, passwords; never commit .env
sudo mkdir -p /srv/ci-runner/work           # RUNNER_WORKDIR from .env (same path inside and outside the container)

scripts/init-registry.sh --install-ca       # self-signed certificate + htpasswd under data/; prints the generated password once
docker compose up -d registry pact-broker   # also starts pact-broker-db
curl --cacert data/certs/registry.crt -u ci https://localhost:5443/v2/      # {} when the credentials are right
curl -u pact http://localhost:9292/diagnostic/status/heartbeat                # {"ok":true,...}
```

`init-registry.sh` is idempotent (`--force` regenerates), takes the registry password from `REGISTRY_PASSWORD` or generates
one, and with `--install-ca` copies the certificate to `/etc/docker/certs.d/<host:port>/ca.crt` (sudo) so the Docker
daemon trusts it (the daemon, not the CLI, performs login, push and pull; no restart is needed). Run it with
`--host registry.ci.example:5443` when jobs or other machines reach the registry by name: the name is added to the
certificate. For `localhost` the Docker daemon accepts the registry even without the certificate.

### Repository secrets and variables

Set them once (values are yours; nothing here is committed):

```bash
gh variable set REGISTRY_HOST --body 'localhost:5443'
gh secret set REGISTRY_USERNAME            # ci
gh secret set REGISTRY_PASSWORD            # the password printed by init-registry.sh
gh secret set PACT_BROKER_URL --body 'http://localhost:9292'
gh secret set PACT_BROKER_USERNAME         # PACT_BROKER_BASIC_AUTH_USERNAME of .env
gh secret set PACT_BROKER_PASSWORD         # PACT_BROKER_BASIC_AUTH_PASSWORD of .env
```

| Name | Kind | Used by | Meaning |
| --- | --- | --- | --- |
| `REGISTRY_HOST` | variable | `service-ci.yml` image job | `host:port` that Docker on the runner host uses for the registry |
| `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` | secrets | push step only | registry basic-auth credentials |
| `PACT_BROKER_URL` | secret | contract steps | base URL of the broker; unset skips every broker step |
| `PACT_BROKER_USERNAME`, `PACT_BROKER_PASSWORD` | secrets | contract steps | broker basic-auth credentials |

### Register the runner

Labels: the runner is started with `LABELS=ecommerce`; GitHub adds `self-hosted`, `Linux` and the architecture, so jobs
select it with `runs-on: [self-hosted, linux, ecommerce]` (all workflows do). Scope: `RUNNER_SCOPE=repo`, `REPO_URL` from
`.env`. Pick one registration mode:

- **B, recommended: registration token fetched on the host.** Leave `ACCESS_TOKEN` empty and run
  `scripts/run-ephemeral.sh` (under systemd, tmux or nohup). Per job it fetches a one-hour registration token with the
  host's `gh` login, starts a freshly created container, waits for it to exit, removes it and empties the work directory.
  No long-lived token is ever in the container's environment.
- **A, simple: PAT in `.env`.** Set `ACCESS_TOKEN` to a fine-grained personal access token (or a GitHub App token)
  restricted to this one repository with "Administration: read and write" (needed to create registration tokens) and
  nothing else, then `docker compose up -d runner`. With `restart: always` the container restarts after each job and
  registers again, but it is restarted, not recreated: its writable layer and the tool cache persist, and the token is
  visible to anything that can `docker inspect` the container, which includes jobs (they have the Docker socket). Rotate
  the token regularly.

Either way `EPHEMERAL=1` (one job per registration), `DISABLE_AUTO_UPDATE=1` (the image carries the runner version) and
`UNSET_CONFIG_VARS=true` (registration settings are removed from the jobs' environment). Check Settings > Actions >
Runners: one runner, labels `self-hosted`, `Linux`, `X64`, `ecommerce`, status Idle. The runner image's `docker compose`
can be checked with `docker run --rm --entrypoint docker myoung34/github-runner:2.337.0-ubuntu-noble compose version`;
the platform workflow falls back to `docker-compose`.

### How the runner reaches the registry and the broker

The runner container uses the **host network** and the host's Docker socket. Hence, inside a job:

- `localhost:5443` is the registry published by Compose on the host and `localhost:9292` the Pact Broker; the
  repository variable `REGISTRY_HOST` and the secret `PACT_BROKER_URL` use these names. `docker login/push` is executed
  by the host's daemon, which trusts the certificate through `/etc/docker/certs.d` (`--install-ca`).
- Tool containers started by a job (`docker run` for Pact CLI, osv-scanner, Trivy, syft) are siblings on the host, which
  is why they use `--network host` where they must reach the broker, and why `RUNNER_WORKDIR` is mounted at the same path
  inside and outside the runner container: `docker run -v "$PWD:$PWD"` then names a path the host can resolve.
- The platform workflow starts the Compose `core` stack on the same host; the gateway on `localhost:8080` is reachable
  by the Gradle acceptance suite for the same reason. Only one such job can run at a time (fixed host ports): run one
  runner replica.

To pull images from another machine, bind the registry to a reachable interface (`REGISTRY_BIND_ADDRESS`), run
`init-registry.sh --host <name>:<port>`, install `data/certs/registry.crt` as `ca.crt` under
`/etc/docker/certs.d/<name>:<port>/` on that machine and `docker login` there.

## Public-repository safeguards

GitHub advises against self-hosted runners on public repositories because any pull request can run code on them. The
stack and the workflows apply these mitigations (specs research.md section 4); they only work together:

1. **No fork pull requests on the runner.** Every job has `if: github.event_name == 'push' ||
   github.event.pull_request.head.repo.full_name == github.repository`; there is no `pull_request_target` and no
   `workflow_run`. A maintainer pushes a fork contribution to a branch of this repository to have it checked.
2. **Settings > Actions > General > "Fork pull request workflows from outside collaborators": "Require approval for all
   outside collaborators"** (`scripts/bootstrap-repo.sh` attempts it through the API; verify it in the UI). The guard in
   the workflow file is only a second barrier: a fork controls its own copy of the file.
3. **Actions are pinned by full commit SHA** with the release in a trailing comment; tool images by tag and digest.
4. **Ephemeral runner**: one job per registration, a new container per job in mode B.
5. **Least privilege**: workflow `permissions: contents: read`, checkout without persisted credentials, secrets only in
   the steps that use them (`REGISTRY_*` in the push step, `PACT_BROKER_*` in the broker steps; never in the Gradle
   `check`), registration token or a repository-scoped PAT or GitHub App, Settings > Actions > General > Workflow
   permissions set to read-only.
6. **Images are scanned (Trivy, osv-scanner) before they are pushed**, and only pushes to `main` publish.
7. **Mounting the Docker socket gives a job root-equivalent control of the host.** Run the stack on a dedicated machine
   or VM that holds nothing else, treat everything on it as disposable, and keep write access to the repository limited
   to people whose code you would let run there. If this cannot be met, run pull-request checks on GitHub-hosted runners
   and keep this runner for post-merge image builds (docs/ci-cd.md).

## Verify the path filters (T108)

`act` is not installed here and does not apply `paths:` filters to a diff, so two checks replace the dry run.

**1. Offline, no GitHub needed.** The path filters of the workflows are parsed and evaluated against changed files:

```bash
.github/scripts/path-filter-check.sh services/cart/domain/src/main/kotlin/Foo.kt     # cart.yml only
.github/scripts/path-filter-check.sh libs/platform-core/src/main/kotlin/Foo.kt       # all seven service workflows
.github/scripts/tests/test-path-filter-check.sh                                      # asserts both, plus the workflow wiring
```

**2. Live, once the runner is registered.** Push a one-line change to one service and watch the runs:

```bash
git switch -c chore/path-filter-check
echo >> services/cart/README.md              # or edit any one line below services/cart/
git commit -am "chore: path filter check" && git push -u origin HEAD
gh pr create --draft --fill
gh run list --branch chore/path-filter-check --json workflowName --jq '.[].workflowName' | sort | uniq -c
```

Expected: one run of `cart` (check `service-ci / cart` with its `gate (cart)` and `image (cart)` jobs) and the repository-wide
`pr-gate`; none of `gateway`, `identity`, `catalog`, `order`, `payment`, `notification` or `platform`. Then change a line under
`libs/platform-core/` and push again: all seven service workflows run (`platform` does not). A change confined to `docs/`
runs only `pr-gate`. Close the draft pull request and delete the branch afterwards. The image is not pushed from a pull
request; to see the push, merge a change to `main` and run `curl --cacert data/certs/registry.crt -u ci
https://localhost:5443/v2/cart/tags/list` (tags `main` and the commit SHA).

## Operations

```bash
docker compose ps                      # registry, pact-broker, pact-broker-db healthy; runner running
docker compose logs -f runner          # registration and job output
docker compose pull && docker compose up -d     # after changing a pinned tag in docker-compose.yml
docker system prune -f --volumes=false          # reclaim build cache and stopped containers (not the registry volume)
```

- Updating a pin: change the tag and digest together (`docker buildx imagetools inspect <image>:<tag>`).
- Runner does not appear: check `REPO_URL`, the token scope, and `docker compose logs runner`.
- Jobs fail on `docker login` with a certificate error: the host's Docker daemon does not trust `data/certs/registry.crt`
  for exactly the `REGISTRY_HOST` value (re-run `init-registry.sh --install-ca`).
- Testcontainers cannot reach its containers: with the host network and the mounted socket this works as on a normal
  Linux host; check that nothing else binds the ports and that `docker ps` works inside the job.
- Images lose their health check: the engine is Podman and the build was not in Docker format (`BUILDAH_FORMAT=docker` is
  set by the runner service and by the workflows).
- Ephemeral behaviour: `scripts/run-ephemeral.sh` sets `RUNNER_RESTART=no` and recreates the container per job; with plain
  `docker compose up -d runner` the container restarts instead (mode A above).
