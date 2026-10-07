# Shared service image recipe

`Dockerfile` is the single multi-stage recipe for every deployable of the platform (the gateway and the
`infrastructure` module of each service). It is used by:

- the Gradle `dockerImage` task of the `docker-image` convention (`./gradlew :services:<name>:infrastructure:dockerImage`;
  its first run compiles every deployable in the shared stage, later runs for the other services reuse it),
- Docker Compose (`platform/compose/docker-compose.yml`, `build:` blocks of `gateway` and the six services).

Per-service Dockerfiles are therefore not needed: the service is chosen with a build argument.

## Build arguments

| Argument | Meaning | Examples |
|---|---|---|
| `SERVICE_MODULE` | Gradle path of the module whose Spring Boot jar goes into the image; required unless `APP_JAR` is set | `:services:gateway`, `:services:catalog:infrastructure` |
| `APP_JAR` | Optional path, inside the build context, of an already built boot jar (CI: the artifact of the `jar` job of `.github/workflows/service-ci.yml`); the `build` stage copies it instead of compiling | `ci-jar/app.jar` |

Always build from the **repository root** (the build context is the whole monorepo, `.dockerignore` trims it):

```bash
docker build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=:services:catalog:infrastructure -t catalog:local .
```

## Stages

1. `build` (`eclipse-temurin:25-jdk`), **shared by every image**: copies the repository and compiles the boot jar of every
   deployable (`services/gateway` and each `services/*/infrastructure`) in **one** Gradle invocation
   (`./gradlew -q --no-daemon :services:gateway:bootJar :services:identity:infrastructure:bootJar ...`), writing
   `/jars/<module path with dashes>.jar` (`:services:catalog:infrastructure` -> `/jars/services-catalog-infrastructure.jar`).
   The Gradle user home is a cache mount (`--mount=type=cache,target=/root/.gradle,sharing=locked`), so the Gradle
   distribution and dependencies are downloaded once per engine; the cache never ends up in an image layer. The stage
   does not declare `SERVICE_MODULE`: its instructions and context are identical for the seven images, so the engine
   builds it once and every later image of `docker compose up --build` reuses it from the layer cache. With `APP_JAR`
   set it copies that jar to `/jars/prebuilt.jar` and compiles nothing.
2. `layers` (`FROM build`): picks `/jars/prebuilt.jar` or the jar of `SERVICE_MODULE` from the shared stage's file
   system (no copy of the seven jars) and extracts the Spring Boot layered jar
   (`-Djarmode=tools extract --layers --launcher`), so that dependencies change rarely and rebuilds only replace the
   small `application` layer.
3. runtime (`eclipse-temurin:25-jre`): non-root user `app` (uid/gid 10001), the four extracted layers, `EXPOSE 8080 8081`
   (API and management), `ENTRYPOINT java ... JarLauncher`.

Never reference `SERVICE_MODULE` in the `build` stage: the build argument would become part of the stage's cache key and
every image would compile again (the 14 to 24 minute cold start this design replaced, T132).

### Shared stage under Podman (buildah)

Verified on 2026-10-03 with Podman 6.0.2 (buildah 1.44.1) behind the docker API and Docker Compose v5.6.0: the first image
(`cart`) runs the Gradle step; for the six others buildah answers `--> Using cache <id>` on that `RUN` (the same image
id each time) and only the `layers` extraction and the runtime `COPY` steps run. No separate base image or build script
is needed. Images must still be built one at a time (`COMPOSE_PARALLEL_LIMIT=1`, the `smoke.sh` default): parallel
builds would all miss the cache and run the Gradle step together on the same `sharing=locked` cache mount.

### Start-up time (SC-006)

Measured on the development machine (macOS, Podman machine with libkrun, 8 CPUs, 11.6 GiB; base images already pulled;
the seven service images removed and the build cache, including the Gradle cache mount, pruned with
`podman image prune --build-cache -f`):

| `docker compose --profile core up -d --build` | Before (one Gradle build per image) | After (shared stage) |
|---|---|---|
| Cold (no images, no build cache, empty Gradle cache mount) | 24 min (2026-10-02), 14 min 30 s (2026-10-03, `smoke.sh`) | **3 min 58 s** until all seven services are healthy (`up` returned after 3 min 38 s; the shared Gradle stage took about 2 min 10 s) |
| Warm, nothing changed (`docker compose build`) | about 14 min | 32 s (every step from the cache) |

A source change rebuilds the shared stage once (all seven jars, with Gradle's build cache inside the cache mount) and
then the seven `layers`/runtime stages. The floor of a cold start on this machine is the single Gradle compilation
(about 2 minutes) plus the start of the JVMs behind their health checks (about 40 s for the last one).

Base images are pinned by digest; refresh them deliberately.

## Runtime settings

- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=40 -XX:MaxDirectMemorySize=128m -Dnetworkaddress.cache.ttl=5` and
  `MALLOC_ARENA_MAX=2`: heap sized to 40 % of the container memory limit (307 MiB of Compose's 768m), Netty's pooled
  direct buffers capped at 128 MiB (they default to the heap size) and two glibc malloc arenas, because a service needs
  300 to 400 MB besides its heap (metaspace and code cache about 160 MB, malloc'ed Netty buffers, arenas, threads). With
  a 75 % heap of 640 MiB, then a 50 % heap of 768 MiB, the kernel OOM-killed identity, catalog and cart under the
  acceptance and performance suites (2026-10-03). Plus a 5 s JVM DNS cache so that DNS-based discovery notices added and removed replicas quickly.
- `HEALTHCHECK` probes `GET /actuator/health/readiness` on port 8081 (bash `/dev/tcp`, the JRE image has no curl) and
  expects `"status":"UP"`. Compose declares the same check (`x-app` in `platform/compose/docker-compose.yml`) and uses it for
  `depends_on: condition: service_healthy` and `docker compose ps`; the CI start-and-health step
  (`.github/scripts/image-health.sh`) polls the same path. Every service and the gateway enable the probe groups
  (`management.endpoint.health.probes.enabled=true`, readiness = `readinessState`, plus the database contributor `db`
  in catalog; docs/service-conventions.md section 2).

## How Compose uses it

```yaml
catalog:
  build:
    context: ../..                          # repository root
    dockerfile: platform/docker/Dockerfile
    args:
      SERVICE_MODULE: :services:catalog:infrastructure
```

`docker compose build catalog` (or `up -d --build catalog`) rebuilds a single service; see `platform/compose/README.md`.

## Podman note

`HEALTHCHECK` is part of the Docker image format only. When building with Podman directly, pass `--format docker`
(or export `BUILDAH_FORMAT=docker`), otherwise the health check is dropped from the image and Compose never sees the
service as healthy. The same applies to `Dockerfile.storefront` below.

## Storefront image

`Dockerfile.storefront` (feature 005, research section 7) packages the web storefront of `frontend/`: the Vite bundle
served by nginx, reachable only through the gateway's `storefront` route (`STOREFRONT_URL=http://storefront:8080` in
Compose). It shares nothing with the JVM recipe above. Compose service: `storefront` (`platform/compose/docker-compose.yml`,
profile `core`, `mem_limit` 64m, network `internal`, no published port).

```bash
docker build -f platform/docker/Dockerfile.storefront -t storefront:dev .      # from the repository root
```

### Stages

1. `build` (`node:24-alpine`): copies `frontend/package.json`, `package-lock.json` and `.npmrc`, runs
   `npm ci --ignore-scripts --silent --no-audit --no-fund` (its own layer, reused while the lock file is unchanged), copies
   the OpenAPI contracts that `frontend/scripts/generate-api.mjs` reads relative to the repository root
   (`specs/004-ecommerce-platform-mvp/contracts/openapi`, `specs/005-storefront-dev-bootstrap/contracts/openapi`), then
   the rest of `frontend/` and runs `npm run build --silent`, which regenerates `src/api/generated` before `vite build`.
   `.dockerignore` excludes `frontend/node_modules`, `frontend/dist`, `frontend/build` and `frontend/src/api/generated`
   (nothing built on the host enters the image) and re-includes the two contract directories and
   `platform/docker/storefront`, which are otherwise outside the context.
2. runtime (`nginxinc/nginx-unprivileged:stable-alpine`): `dist/` at `/usr/share/nginx/html`,
   `platform/docker/storefront/nginx.conf` in place of the image's `/etc/nginx/conf.d/default.conf`, `EXPOSE 8080`,
   `HEALTHCHECK` with busybox `wget -qO- http://127.0.0.1:8080/healthz` (the image has no curl or bash), running as the
   image's unprivileged user (`nginx`, uid 101; pid and temp files under `/tmp`).

`nginx.conf`: `listen 8080`, `server_tokens off`, gzip for the text types; `/healthz` answers 200 `ok`; `/assets/*` (the
hashed bundles) `Cache-Control: public, max-age=31536000, immutable` and 404 when missing (`^~` so the extension rule
below does not override the header); `index.html`, served for `/` and for every extension-less path
(`try_files $uri /index.html`, so `/products/123` and unknown deep links render the storefront's own not-found page),
`Cache-Control: no-store`; any other path whose last segment has a file extension (`/favicon.ico`, `/x/y.js`) is a real
file or 404, never the shell. No security headers: the gateway sets CSP, `X-Frame-Options` and the rest for the
storefront route (`specs/005-storefront-dev-bootstrap/contracts/gateway-routes.md`).

### Digests and how to refresh them

| Stage | Image | Digest (manifest list, resolved 2026-10-06) |
|---|---|---|
| `build` | `node:24-alpine` | `sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1` |
| runtime | `nginxinc/nginx-unprivileged:stable-alpine` | `sha256:15c994d10d6d78658721c3bcafff14cb281fba2a4bdf9d5ba92c416a472516e3` |

The digests are those of the multi-architecture manifest lists, so the same line builds on arm64 (the development
machine) and amd64 (CI). To refresh one, read `Docker-Content-Digest` from the registry (`skopeo inspect --raw
docker://docker.io/library/node:24-alpine | sha256sum` when skopeo is installed; otherwise the registry API: a pull token
from `https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/node:pull`, then
`curl -sI -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json" https://registry-1.docker.io/v2/library/node/manifests/24-alpine`),
paste it after the tag in `Dockerfile.storefront`, update the table and rebuild. `docker manifest inspect` does the same
on a Docker engine; Podman's docker API does not implement it.

### Build time

Measured on the development machine (macOS, Podman 6.0.2 behind the docker API, `BUILDAH_FORMAT=docker`, one build at a
time):

| `docker build -f platform/docker/Dockerfile.storefront .` | Time |
|---|---|
| Cold (both base images pulled, `npm ci` and `vite build` run) | **28.7 s** |
| Warm, only `nginx.conf` changed (Node stage from the cache) | 6.2 s |

Well under the "storefront build about 1 min" budget of the plan (SC-009): added to the shared JVM stage, the cold
`core` start stays under 5 minutes. The image serves `index.html` (447 bytes) and two hashed assets at this stage of the
feature; the bundle grows with the storefront, the recipe does not change.
