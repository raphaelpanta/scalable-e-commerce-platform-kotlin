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
| `REGISTRY_HOST` | variable | `service-ci.yml` and `storefront.yml` image and publish jobs | `host:port` that Docker on the runner host uses for the registry |
| `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` | secrets | push step only | registry basic-auth credentials |
| `PACT_BROKER_URL` | secret | contract steps | base URL of the broker; unset skips every broker step |
| `PACT_BROKER_USERNAME`, `PACT_BROKER_PASSWORD` | secrets | contract steps | broker basic-auth credentials |
| `PACT_CAN_I_DEPLOY_RETRIES` | variable (optional) | `can-i-deploy` | polls every 10 s while verification results are unknown (default 24) |

### Register the runner

Labels: the runner is started with `LABELS=ecommerce`; GitHub adds `self-hosted`, `Linux` and the architecture, so jobs
select it with `runs-on: [self-hosted, linux, ecommerce]` (all workflows do). Scope: `RUNNER_SCOPE=repo`, `REPO_URL` from
`.env`. Pick one registration mode:

- **B, recommended: on-demand runner with a registration token fetched on the host.** Leave `ACCESS_TOKEN` empty and run
  `scripts/run-ephemeral.sh` (under systemd, tmux or nohup). While no workflow run is queued or in progress, no runner
  is up. When work appears, it fetches a one-hour registration token with the host's `gh` login and starts a freshly
  created, non-ephemeral container that serves job after job (no restart and new registration per job). Once the runner
  has been idle (not busy and nothing queued) for `RUNNER_IDLE_TIMEOUT` seconds (default 900; poll interval
  `RUNNER_POLL_INTERVAL`, default 30, both in `.env` or the environment), it stops and removes the container, deletes
  the registration through the API and empties the work directory, then waits for the next queued run. Ctrl-C or
  SIGTERM stops the script once the runner is idle; a running job is not cut off. No long-lived token is ever in the
  container's environment. Jobs of one session share the container and its tool cache (the checkout is cleaned by
  `actions/checkout`); a new session always starts from a new container.
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
- Resources: the runner container is capped at `RUNNER_CPUS` (9) and `RUNNER_MEM_LIMIT` (10g), below the engine VM
  (10 CPUs and 14 GiB on the development Mac), because the containers that jobs start through the Docker socket
  (Testcontainers, the platform workflow's stack) run outside that cap and need the rest. Inside the runner, Gradle uses
  6 workers and a 4 GB daemon heap (`RUNNER_GRADLE_OPTS`, passed to the jobs as `GRADLE_OPTS`) instead of the 4 workers
  and 3 GB of `gradle.properties`. The engine VM itself is sized with `podman machine set --cpus --memory` while stopped.
- On-demand behaviour: `scripts/run-ephemeral.sh` sets `RUNNER_RESTART=no`, keeps one container per session of jobs and
  removes it after `RUNNER_IDLE_TIMEOUT`; its log (for example `~/ci-runner/run-ephemeral.log` under nohup) has one
  line per start and stop. With plain `docker compose up -d runner` the container restarts after every job instead
  (mode A above).
- Jobs stay queued: check that the script is running (`pgrep -fl run-ephemeral`) and read its log. A failed
  registration-token request is retried and the loop carries on.
