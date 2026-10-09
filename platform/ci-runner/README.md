# CI runner stack (feature 004)

Everything the per-service and platform pipelines need on the CI host (research.md sections 4 and 5, tasks T108 and
T109): an **ephemeral GitHub Actions runner**, a **private image registry** (`registry:3`, basic auth, TLS) and the
**Pact Broker** with its PostgreSQL. Workflows are documented in [docs/ci-cd.md](../../docs/ci-cd.md); this page covers
the host.

| Service | Image | Published on the host |
| --- | --- | --- |
| `runner` | `myoung34/github-runner:2.337.0-ubuntu-noble` (tracks `actions/runner` 2.337.0; includes Docker CLI, Compose, git, jq); labels `ecommerce`, `ecommerce-light` | nothing (host network) |
| `runner-light` (profile `light`) | the same image, 2 CPUs and 1536m; label `ecommerce-light` only: image builds and scans, publish, the aggregate jobs, hook tests | nothing (host network) |
| `registry` | `registry:3.1.2`, htpasswd (bcrypt) auth, TLS | `${REGISTRY_BIND_ADDRESS}:${REGISTRY_PORT}` = `127.0.0.1:5443` |
| `pact-broker`, `pact-broker-db` | `pactfoundation/pact-broker:3.0.0-pactbroker2.121.2`, `postgres:18-alpine` (same as the `ci` profile of `platform/compose`) | `${PACT_BROKER_BIND_ADDRESS}:${PACT_BROKER_PORT}` = `127.0.0.1:9292` |

Images are pinned by tag (the runner and the registry also by digest). Restart policies: `unless-stopped` for the
registry, the broker and its database; `always` for the runner (see "Ephemeral behaviour"). Volumes: `registry-data`,
`pact-broker-db-data`, `runner-cache` and `runner-toolcache` (see "Caches"), the work directories (`ci-runner-work`,
`ci-runner-work-light`, or `RUNNER_WORKDIR`), and the bind-mounted `data/` (certificate, htpasswd; git-ignored).

## Host requirements

A dedicated Linux machine or VM (see the warning below) with Docker Engine or Podman and Compose, at least 4 CPU cores
and 16 GB of memory (Gradle, Testcontainers, the platform workflow's stack of about 8 GB), about 20 GB of free disk for
the caches, the work directories and the images, outbound access to GitHub, Maven Central, the Gradle Plugin Portal,
`api.osv.dev` and the registries of the tool images, and `gh` (logged in) if you use `scripts/run-ephemeral.sh`.

The runners are always Linux containers, whatever the machine: on macOS the engine's Linux VM (Podman machine, Docker
Desktop, Colima) is the host of this stack. `scripts/run-ephemeral.sh` runs on the Mac or on the Linux host (bash 3.2 or
later, BSD or GNU tools) and takes everything it sizes or mounts from the engine, never from the machine it runs on:

- **CPUs and memory** come from `docker info` (the VM's 10 CPUs and 14 GiB on the development Mac, not the Mac's own).
- **Work directories** are engine volumes on the VM's own disk. A macOS folder shared into the VM (`/Users/...`, virtiofs)
  is 30 to 50 times slower for the small files of a checkout, a Gradle build or an npm install (measured on the
  development Mac: 3,000 small files written in 2.8 s on the share, 0.09 s on the VM disk), so never use one as
  `RUNNER_WORKDIR`.
- **SELinux** (Fedora CoreOS under `podman machine`, Fedora, RHEL) keeps a confined container from opening the engine
  socket; the runners run with `security_opt: label=disable` so that `docker` and Testcontainers work inside jobs.
- **Podman**: rootful (`podman machine set --rootful`) serves `/var/run/docker.sock`; for rootless Podman set
  `DOCKER_SOCKET=/run/user/<uid>/podman/podman.sock` in `.env`. Images are built with `BUILDAH_FORMAT=docker` (set by
  the runners and the workflows) so that they keep their `HEALTHCHECK`.

The storefront (feature 005) adds two needs on the runner. The storefront pipeline and the browser acceptance steps of
`platform.yml` install Node 24 with `actions/setup-node` and need outbound access to the npm registry (`registry.npmjs.org`)
and, for Chromium, to the Playwright download hosts (`cdn.playwright.dev`, `playwright.download.prss.microsoft.com`).
`npx playwright install --with-deps chromium` also installs the browser's system libraries through `apt`, so the runner
container needs root or passwordless sudo (the `myoung34/github-runner` image has the latter); `platform.yml` checks for
it and falls back to `npx playwright install chromium`, in which case the libraries (`libnss3`, `libgbm1`, `libasound2t64`
and the other packages `npx playwright install-deps chromium` lists) must already be part of the runner image. Allow about
1 GB of free disk for the Chromium download and the npm cache of a cache-cold run.

## Setup

```bash
cd platform/ci-runner
cp .env.example .env && $EDITOR .env        # REPO_URL, passwords (RUNNER_WORKDIR stays empty); never commit .env

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
| `REGISTRY_HOST` | variable | `service-ci.yml` and `storefront.yml` image and publish jobs | `host:port` that Docker on the runner host uses for the registry |
| `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` | secrets | push step only | registry basic-auth credentials |
| `PACT_BROKER_URL` | secret | contract steps | base URL of the broker; unset skips every broker step |
| `PACT_BROKER_USERNAME`, `PACT_BROKER_PASSWORD` | secrets | contract steps | broker basic-auth credentials |
| `PACT_CAN_I_DEPLOY_RETRIES` | variable (optional) | `can-i-deploy` | polls every 10 s while verification results are unknown (default 24) |

### Register the runner

Labels: `runner` is started with `LABELS=ecommerce,ecommerce-light`, `runner-light` with `LABELS=ecommerce-light`;
GitHub adds `self-hosted`, `Linux` and the architecture. Jobs that run Gradle, Node or the platform stack select
`runs-on: [self-hosted, linux, ecommerce]` (only `runner` serves them, one at a time, which also keeps the platform
stack's fixed host ports to one job); jobs that only drive the engine or aggregate results select
`[self-hosted, linux, ecommerce-light]` and run on whichever of the two is free, so an image build no longer waits for a
Gradle job. Scope: `RUNNER_SCOPE=repo`, `REPO_URL` from `.env`. Pick one registration mode:

- **B, recommended: on-demand runners with a registration token fetched on the host.** Leave `ACCESS_TOKEN` empty and
  run `scripts/run-ephemeral.sh` (under systemd, launchd, tmux or nohup). While no workflow run is queued or in progress,
  no runner is up. When work appears, it fetches a one-hour registration token with the host's `gh` login and starts
  freshly created, non-ephemeral containers (`runner` and, unless `RUNNER_LIGHT=false`, `runner-light`) that serve job
  after job (no restart and new registration per job). Once a runner has been idle (not busy and nothing queued) for
  `RUNNER_IDLE_TIMEOUT` seconds (default 900; poll interval `RUNNER_POLL_INTERVAL`, default 30, both in `.env` or the
  environment), it stops and removes its container, deletes the registration through the API and empties the work
  directory; the next queued run starts it again. At startup and before each start it also deletes offline
  registrations with its name prefix that a crash left behind. It prunes dangling images and build cache older than
  24 hours when a session ends, and those older than an hour as soon as the engine disk has less than
  `RUNNER_MIN_FREE_GIB` (default 8) free, checked every ten polls: a day of image builds left 90 dangling images (18 GB)
  on the development Mac's VM and filled its disk (`RUNNER_PRUNE=false` keeps them). Ctrl-C or SIGTERM stops the script once the
  runners are idle; a running job is not cut off. No long-lived token is ever in a container's environment. Jobs of one
  session share the container and the caches (the checkout is cleaned by `actions/checkout`); a new session always
  starts from a new container. `scripts/run-ephemeral.sh --print-config` prints the sizing and the work directories it
  would use, without starting anything.
- **A, simple: PAT in `.env`.** Set `ACCESS_TOKEN` to a fine-grained personal access token (or a GitHub App token)
  restricted to this one repository with "Administration: read and write" (needed to create registration tokens) and
  nothing else, then `docker compose up -d runner`. With `restart: always` the container restarts after each job and
  registers again, but it is restarted, not recreated: its writable layer and the tool cache persist, and the token is
  visible to anything that can `docker inspect` the container, which includes jobs (they have the Docker socket). Rotate
  the token regularly.

Mode A runs with `EPHEMERAL=1` (one job per registration); mode B passes an empty `EPHEMERAL` (one registration per
session) and `DISABLE_AUTOMATIC_DEREGISTRATION=true`, because the image's own deregistration needs the registration
token that `UNSET_CONFIG_VARS` has already removed, fails and leaves an offline runner behind; the script deletes the
registration itself. Both use `DISABLE_AUTO_UPDATE=1` (the image carries the runner version) and
`UNSET_CONFIG_VARS=true` (registration settings are removed from the jobs' environment). Check Settings > Actions >
Runners: `ecommerce-<n>` with labels `self-hosted`, `Linux`, the architecture (`X64` or `ARM64`), `ecommerce`,
`ecommerce-light`, and `ecommerce-light-<n>` with `ecommerce-light`, status Idle. The runner image's `docker compose`
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

### Pact Broker webhooks (provider pipelines on consumer publication)

A consumer pipeline publishes its pacts (`service-ci.yml`, step "Publish the consumer pacts"). When the content of a
pact changed, the broker fires the event `contract_content_changed`; one webhook per provider turns it into a
`workflow_dispatch` of that provider's workflow (`.github/workflows/<provider>.yml`), whose `gate` job then runs
`<Ctx>BrokerVerificationTest` against exactly that pact (inputs `pact-url` and `consumer`, passed to the build as
`PACT_URL` and `PACT_CONSUMER`) and publishes the verification result. The consumer's `can-i-deploy` step waits for that
result (`--retry-while-unknown`, `PACT_CAN_I_DEPLOY_RETRIES` x 10 s). A broken contract therefore fails the provider run,
naming the consumer and the interaction, and the consumer's `can-i-deploy`, naming the pact.

1. Create a fine-grained personal access token (or a GitHub App installation token) restricted to this repository with
   **Actions: read and write** and nothing else; it is what the broker sends to the GitHub API. Rotate it like the
   runner token.
2. The broker only calls hosts it is allowed to: `docker-compose.yml` sets `PACT_BROKER_WEBHOOK_SCHEME_WHITELIST=https`
   and `PACT_BROKER_WEBHOOK_HOST_WHITELIST=api.github.com`.
3. Create one webhook per provider (`identity`, `catalog`, `cart`, `order`, `payment`, `notification`); the `reason`
   names the event, the consumer, its version and branch:

```bash
GH_REPO=owner/repository             # this repository
GH_DISPATCH_TOKEN=github_pat_...     # step 1; never commit it
for provider in identity catalog cart order payment notification; do
  curl -fsS -u "$PACT_BROKER_USERNAME:$PACT_BROKER_PASSWORD" -H 'Content-Type: application/json' \
    -X PUT "http://localhost:9292/webhooks/ecommerce-dispatch-$provider" --data @- <<JSON
{
  "description": "Run the $provider pipeline when a consumer changes a pact",
  "provider": { "name": "$provider" },
  "events": [ { "name": "contract_content_changed" } ],
  "request": {
    "method": "POST",
    "url": "https://api.github.com/repos/$GH_REPO/actions/workflows/$provider.yml/dispatches",
    "headers": {
      "Accept": "application/vnd.github+json",
      "Authorization": "Bearer $GH_DISPATCH_TOKEN",
      "Content-Type": "application/json"
    },
    "body": {
      "ref": "main",
      "inputs": {
        "reason": "contract_content_changed \${pactbroker.consumerName} \${pactbroker.consumerVersionNumber} on \${pactbroker.consumerVersionBranch}",
        "pact-url": "\${pactbroker.pactUrl}",
        "consumer": "\${pactbroker.consumerName}"
      }
    }
  }
}
JSON
done
```

`PUT /webhooks/<id>` with a fixed id (letters, digits and dashes, at least 16 characters) keeps the command
idempotent; the `${pactbroker.*}` placeholders are expanded by the broker, not by the shell. Check one with
`curl -u ... -X POST http://localhost:9292/webhooks/ecommerce-dispatch-catalog/execute` (a run of `catalog` appears with
the reason in its summary) and read the delivery logs under `/webhooks/ecommerce-dispatch-catalog`. The dispatched run
checks out `main`, so it verifies the provider's main version against the changed pact; `workflow_dispatch` can only be
triggered with a token that has write access to the repository, so it does not widen who can run code on the runner.
Without webhooks nothing breaks: each provider pipeline still verifies the broker's pacts (consumer versions on `main`,
deployed or released, and on the same branch) whenever it runs.

## Caches

Everything a job would otherwise download or rebuild lives on the runner host, so no workflow uses the GitHub cache
service (restoring and saving gigabytes over the network costs more than it saves when the data already sits on the
same disk):

| What | Where | Used by |
| --- | --- | --- |
| Gradle user home: dependencies, wrapper distribution, JDK toolchains, **local build cache** | volume `runner-cache`, `/opt/ci-cache/gradle` (`GRADLE_USER_HOME`) | every Gradle job; `setup-gradle` runs with `cache-disabled: true` |
| Gradle configuration cache | `/opt/ci-cache/gradle-configuration-cache/<workspace>`, linked into `.gradle/` by `.github/scripts/ci-cache.sh gradle` | every Gradle job |
| npm download cache | `/opt/ci-cache/npm` (`npm_config_cache`); `npm ci --prefer-offline` | the storefront, verify, mutation and platform jobs |
| Playwright browsers | `/opt/ci-cache/ms-playwright` (`PLAYWRIGHT_BROWSERS_PATH`) | the platform job |
| JDK and Node of `setup-java` / `setup-node` | volume `runner-toolcache`, `/opt/hostedtoolcache` | every job that sets them up |
| Image layers, tool images (Pact CLI, osv-scanner, Trivy, syft, shellcheck), the visual suite's `node_modules` | the engine | image, scan and platform jobs |
| Trivy vulnerability database | engine volume `trivy-cache` | image jobs |
| Stryker results of a commit (the storefront gate's run, reused by the pr-gate mutation job on the same merge commit; never another commit's) | `/opt/ci-cache/stryker/<sha>.json`, by `.github/scripts/ci-cache.sh stryker restore|save` | storefront gate, pr-gate mutation |
| gitleaks (pinned, SHA-256 checked) | `/opt/ci-cache/tools`, put on the PATH by `.github/scripts/ci-cache.sh gitleaks` | verify (the script tests of the pre-push hook) |

The build cache only pays off when task outputs do not change from one build to the next: the boot applications' build
information therefore carries no build time (`kotlin-boot-app` convention), otherwise every test layer, Pitest and the
boot jar would run again in every job. With the cache warm, a service pipeline after `verify` (or the other way round)
takes its compilation, unit, integration and acceptance tests and Pitest from the cache; the contract layers run again
on purpose (their pacts live outside the task outputs, and broker verification publishes results).

Images of pull-request runs are removed by the image job; on `main` the image stays in the engine for the publish job
(both runners use the same engine; the repository variable `IMAGE_HANDOVER=artifact` restores the artifact hand-over for
runners on several engines), which checks its ID and is followed by the removal in the aggregate job.

To start from a cold cache: `docker volume rm ci-runner_runner-cache ci-runner_runner-toolcache` while no runner is up.

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
4. **Ephemeral runners**: a new container and registration per session of jobs in mode B (one job per registration in
   mode A); the work directory is emptied when a session ends.
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
- Testcontainers cannot reach its containers ("Could not find a valid Docker environment") or `docker` fails with
  "permission denied" on `/var/run/docker.sock`: SELinux separates the runner from the socket. The runners carry
  `security_opt: label=disable`; a container started before that change must be recreated (restart
  `scripts/run-ephemeral.sh`). Check with `docker exec ci-runner-runner-1 docker version` while a runner is up. Ryuk is
  off (`TESTCONTAINERS_RYUK_DISABLED=true`), as on the development machine.
- Images lose their health check: the engine is Podman and the build was not in Docker format (`BUILDAH_FORMAT=docker` is
  set by the runner service and by the workflows).
- Resources: `runner` is capped at `RUNNER_CPUS` and `RUNNER_MEM_LIMIT`, by default all engine CPUs but one and the
  engine memory less 4 GiB (9 CPUs and 10g on the development Mac's 10-CPU, 14 GiB VM), because the containers that jobs
  start through the Docker socket (Testcontainers, the platform workflow's stack, image builds) run outside that cap and
  need the rest; `runner-light` is capped at 2 CPUs and 1536m. Inside `runner`, Gradle uses up to 6 workers and a 4 GB
  daemon heap (`RUNNER_GRADLE_OPTS`, passed to the jobs as `GRADLE_OPTS`) instead of the 4 workers and 3 GB of
  `gradle.properties`. The engine VM itself is sized with `podman machine set --cpus --memory` while stopped; its disk
  only grows (`podman machine set --disk-size`). `scripts/run-ephemeral.sh --print-config` shows the values in effect.
- Disk: the script prunes when the engine disk has less than `RUNNER_MIN_FREE_GIB` free and warns in its log when that
  was not enough; `docker system df` shows what the images use.
- On-demand behaviour: `scripts/run-ephemeral.sh` sets `RUNNER_RESTART=no`, keeps one container per runner and session
  of jobs and removes it after `RUNNER_IDLE_TIMEOUT`; its log (for example `~/ci-runner/run-ephemeral.log` under nohup)
  has one line per start and stop. With plain `docker compose up -d runner` the container restarts after every job instead
  (mode A above).
- Jobs stay queued: check that the script is running (`pgrep -fl run-ephemeral`) and read its log. A failed
  registration-token request is retried and the loop carries on.
