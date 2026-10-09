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
   system (no copy of the seven jars) and extracts the Spring Boot layers as a plain `java -jar` layout
   (`-Djarmode=tools extract --layers --application-filename app.jar`: `app.jar` plus `lib/*.jar`), so that dependencies
   change rarely and rebuilds only replace the small `application` layer. The JarLauncher layout is not used because an
   AOT cache can only hold classes loaded from jar files. The stage also writes two throw-away keys for the training run
   (identity's Ed25519 signing key, the gateway's AES-256 browser session key); they are bind-mounted into that one
   `RUN` and never copied into an image.
3. runtime (`eclipse-temurin:25-jre`): non-root user `app` (uid/gid 10001), a static busybox `wget` from
   `busybox:1.37-musl` for the health check, the four extracted layers, the
   `JAVA_TOOL_OPTIONS` of "Runtime settings", the **AOT cache training run** (below), `EXPOSE 8080 8081` (API and
   management), `ENTRYPOINT java -XX:AOTCache=app.aot -jar app.jar`.

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

Memory is what bounds how many replicas fit on a node, so the runtime is tuned for it (feature 008, measured on
2026-10-08; the numbers are in "Memory measurements" below).

**Base image.** The glibc (Ubuntu) JRE. The Alpine (musl) JRE was built and measured too: the same memory within a few
MiB, but about 20 % less throughput for the stack under the performance profile (most likely musl's slower allocator
and string routines on the request path), so the runtime stays on glibc. A container runs no OS processes besides the JVM: the
larger base costs disk, not memory. Alpine is kept where it is free (PostgreSQL, the native Kafka broker, nginx).

**`JAVA_TOOL_OPTIONS`** (one service measured alone, 1 CPU, 768 MiB, under load: heap committed was only 70 to 100 MiB
of its 307 MiB ceiling; metaspace, about 85 MiB, JIT-compiled code, about 65 MiB, and native allocations made up the rest):

| Option | Why |
|---|---|
| `-XX:+UseSerialGC` | explicit: the JVM switches to G1 (remembered sets, more threads) as soon as a pod gets 2 CPUs |
| `-XX:+UseCompactObjectHeaders` | 8-byte object headers (JDK 25, JEP 519): 10 to 20 % less heap for the same objects |
| `-XX:MaxRAMPercentage=50` | heap ceiling relative to the container limit: 256 MiB of the 512m Compose default. With the AOT cache the anonymous off-heap memory is about 130 to 180 MiB (it was 300 to 400 MB, which is why 50 % of 768 MiB was OOM-killed before); a 40 % heap at 512m collected too often on the browse path and lost about 10 % of the throughput |
| `-Xss1m` | 1 MiB thread stacks instead of the 2 MiB aarch64 default |
| `-XX:MaxDirectMemorySize=128m` | Netty's pooled direct buffers default to the heap size; capped as before |
| `-Djdk.nio.maxCachedBufferSize=262144` | bounds the per-thread temporary direct buffers of blocking NIO (Flyway's JDBC, the Kafka client) |
| `-XX:TrimNativeHeapInterval=30000` and `MALLOC_ARENA_MAX=2` | glibc gives freed malloc memory back to the OS every 30 s and keeps two arenas instead of eight per CPU of the machine |
| `-Dsun.net.inetaddr.ttl=5` | 5 s JVM DNS cache, so that DNS discovery (Compose service names, later Kubernetes Services) drops removed replicas quickly (research.md section 2, FR-024, SC-008). The former `-Dnetworkaddress.cache.ttl=5` is a *security* property: set as a system property it had no effect and the JVM kept its 30 s default |

Measured and left out: **C1 only** (`-XX:TieredStopAtLevel=1`) saves another 60 to 90 MiB per service and halves the
start-up time, but cost the stack about 30 % of its throughput; enable it for one container with
`JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1` (read after `JAVA_TOOL_OPTIONS`) where memory matters more than speed.
**`-XX:MaxHeapFreeRatio=30`** (Serial GC shrinking the heap after collections) cost about 10 % of the throughput for
little memory.

**AOT cache** (JDK 25, JEP 483/514/515). The last build step starts the application once with
`-XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh`: the JVM records the classes it loaded and linked and the
method profiles, and exits once the Spring context is refreshed. At run time `-XX:AOTCache=app.aot` maps that file
instead of parsing, verifying and linking the classes again: metaspace drops from about 80 to 30 MiB, the class data
becomes shared, read-only, file-backed memory (the kernel can drop it under pressure), and a service starts in about
half the time. The training run needs no database, broker or collector: it runs with `SPRING_FLYWAY_ENABLED=false`,
`SPRING_KAFKA_LISTENER_AUTO_STARTUP=false`, `SPRING_KAFKA_ADMIN_AUTO_CREATE=false` and throw-away values for what a
service refuses to start without (`<CTX>_DB_USER`/`_PASSWORD`, `INTERNAL_API_TOKEN`, `IDENTITY_SIGNING_KEY`,
`BROWSER_SESSION_KEY`). It adds 10 to 20 s per image. A cache is only valid for the JRE and the JVM options that
recorded it, so a failed training run fails the build instead of shipping an image whose JVM would log AOT errors at
every start. A service that gains a new mandatory setting needs a throw-away value in that `RUN` step.

**Health check.** `HEALTHCHECK` runs busybox `wget -qO /dev/null http://127.0.0.1:8081/actuator/health/readiness`
(the JRE image has no curl or wget; the static busybox of the `probe` stage is the same in the native image), which
fails on the 503 the readiness group answers until the service is ready. Compose declares the same check (`x-app` in `platform/compose/docker-compose.yml`) and uses it for
`depends_on: condition: service_healthy` and `docker compose ps`; the CI start-and-health step
(`.github/scripts/image-health.sh`) polls the same path. Every service and the gateway enable the probe groups
(`management.endpoint.health.probes.enabled=true`, readiness = `readinessState`, plus the database contributor `db`
in catalog; docs/service-conventions.md section 2).

## Memory measurements

Measured on 2026-10-08 on the development machine (macOS, Podman 6.0.2 machine with libkrun, 8 CPUs, 13 GiB, freshly
restarted, nothing else running), profile `core` with `platform/perf/compose.perf.yml`, the 10,000-product dataset and a
reduced k6 profile (`BROWSE_VUS=200 CHECKOUT_VUS=20 RAMP_UP=30s DURATION=2m`, k6 inside the stack network). Memory is
`docker stats` (cgroup usage, page cache included) sampled every 5 s: idle after start-up, peak during the run.

**Whole `core` stack** (16 containers):

| Setup | Idle | Peak | Throughput | Browse p95 | k6 thresholds |
|---|---|---|---|---|---|
| Before (Ubuntu JRE, 40 % of 768m, JVM Kafka, Compose defaults), two runs | 2,634 to 2,669 MiB | 4,321 to 4,374 MiB | 155 to 157 req/s | 955 to 958 ms | `browse_list` p95 at 992 to 1,036 ms |
| **After, default** (glibc JRE + AOT cache, 50 % of 512m, identity and catalog 640m, native Kafka, smaller PostgreSQL), four runs | 2,094 to 2,111 MiB | **3,139 to 3,173 MiB** | **182 to 185 req/s** | **73 to 233 ms** | all pass |
| The same with every service at 512m, five runs | 2,117 to 2,140 MiB | 3,073 to 3,119 MiB | 184 to 185 req/s | 68 to 107 ms | all pass |
| The same with every service at 512m, a sixth run (outlier, see below) | 2,244 MiB | 3,451 MiB | 124 req/s | 2,143 ms | browse failed, 2.9 % checkout errors |
| After, the same settings with the 50 % heap given as `JDK_JAVA_OPTIONS` (images before the last rebuild) | 2,099 MiB | 3,281 MiB | 161 req/s | 905 ms | `browse_list` p95 at 1,008 ms |
| After, same images at 768m | 2,112 MiB | 3,453 MiB | 159 req/s | 805 ms | all pass |
| **Native images** (`compose.native.yml`, 256m per service) with the same Compose settings | 1,337 MiB | **1,867 MiB** | **184 req/s** | **104 ms** | all pass |

The outlier was the first of the six runs with every service at 512m (identical images and settings, nothing else
running on the machine, no restart or OOM kill, the AOT cache verified as mapped): during about ten seconds catalog
stopped answering cart's internal calls (cart answered 503 "catalogue unavailable") and browse latency rose with it;
the five runs after it were clean, with no cgroup memory-pressure event (`memory.events`). The outlier was the one run in
which catalog reached its 512m limit (504 MiB), and identity held 470 to 490 MiB of anonymous memory against the same
limit in every run (Argon2id fills its heap), so both now get 640m (`CATALOG_MEM_LIMIT`, `IDENTITY_MEM_LIMIT`); the runs
with that setting are listed in the table. Throughput varies between runs on this machine far
more than memory does; every run of every after setup stayed well below the before peak.

Functional check on both the JVM default and the native stack (with `compose.perf.yml`): the acceptance suite's fast
subset, `GATEWAY_URL=http://localhost:8080 ./gradlew -q :acceptance:test -Dcucumber.filter.tags="not @slow and not @chaos
and not @observability"` (docs/acceptance.md), 95 scenarios passed, none failed; again with the final limits (identity
and catalog at 640m).

Rejected along the way (same profile): the Alpine (musl) JRE, 120 to 138 req/s at a 1.3 to 1.7 s p95 for the same
memory; C1 only, 112 req/s and a 1.7 s p95 for 60 to 90 MiB less per service; `MaxHeapFreeRatio=30`, about 10 % of the
throughput; a 40 % heap at 512m, 149 req/s and a 1.15 s p95.

**Per container, peak under load** (MiB):

| | gateway | identity | catalog | cart | order | payment | notification | Kafka | each PostgreSQL |
|---|---|---|---|---|---|---|---|---|---|
| Before | 518 | 534 | 566 | 370 | 369 | 415 | 338 | 803 | 54 to 86 |
| After, JVM default | 368 | 460 | 510 | 331 | 339 | 321 | 315 | 337 | 34 to 78 |
| After, native | 237 | 129 | 241 | 200 | 226 | 75 | 73 | 362 | 33 to 79 |

Peaks include page cache: a native service's anonymous memory stays at 60 to 120 MiB, the rest is its mapped
executable, which the kernel reclaims before the limit is reached.

The limits drop with them: a service from 768m to 512m (identity and catalog 640m; 256m native), Kafka from 1g to 384m,
a database from 256m to 192m, Mailpit from 512m to 128m; the seven services, Kafka, six databases and Mailpit reserve
5.4 GiB instead of 8.3 GiB (3.4 GiB with the native services).

**One service alone** (catalog, 1 CPU, 768m, 60 s of parallel GETs; anonymous memory, i.e. what the kernel cannot reclaim):
the former image peaked at 335 to 345 MiB, the tuned JVM flags alone at 326 MiB, plus the AOT cache at 279 MiB (metaspace
80 → 34 MiB; with C1 only it reached 203 MiB and was ready in 11 s instead of 25 s), the native image at 115 MiB (ready
in 1 s).

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
service as healthy. The same applies to `Dockerfile.native` and `Dockerfile.storefront` below.

## Native images

`Dockerfile.native` builds one deployable as a GraalVM native executable: no JIT, no metaspace, no class loading at run
time, so a service needs a fraction of the JVM's memory and is ready in about a second. It is an alternative to the JVM
recipe, not a replacement: build it per service, or for the whole stack with the Compose override
`platform/compose/compose.native.yml` (images tagged `:native`, `NATIVE_SERVICE_MEM_LIMIT`, default 256m).

```bash
docker build -f platform/docker/Dockerfile.native --build-arg SERVICE_MODULE=:services:catalog:infrastructure -t catalog:native .
COMPOSE_PARALLEL_LIMIT=1 docker compose -f docker-compose.yml -f compose.native.yml --profile core up -d --build   # from platform/compose
```

How it is built, without touching the repository's Gradle build:

1. `build` (`ghcr.io/graalvm/native-image-community:25`): `./gradlew --init-script platform/docker/native/native-image.init.gradle.kts
   <module>:nativeCompile`. The init script puts GraalVM Native Build Tools (version `native-build-tools` in
   `gradle/libs.versions.toml`, passed as `NATIVE_BUILD_TOOLS_VERSION`) on the build script class path and applies it to
   every Spring Boot application; Spring Boot reacts with its AOT processing (`processAot`) and the native binary
   configuration. `verify`, the JVM images and every build file are unaware of it. `NATIVE_IMAGE_OPTIONS` adds:
   - `-J-Xmx${NATIVE_IMAGE_XMX}` (build argument, default `6g`): the builder heap. Left alone, native-image takes 75 %
     of the machine's memory and drove a 13 GiB Podman machine that also ran the stack out of memory.
   - `-march=compatibility`: runs on any CPU of the architecture.
   - `-H:Preserve=package=com.ecommerce.*`: keeps every class of the platform's own packages with its reflection
     metadata. The services serialise DTOs and events with Jackson from functional routes and Kafka listeners, which
     Spring's AOT processing cannot see; without it the responses and events would lose their fields.
   - `-H:IncludeResources=db/.*`: every Flyway script. Spring's hints cover only `db/migration`, and Flyway silently
     skipped `db/messaging` (outbox and processed_event tables) and `db/seed`.
   - `-H:ConfigurationFileDirectories=.../native/metadata`: `reachability-metadata.json` registers the JDK management
     methods Micrometer's process and file-descriptor metrics call reflectively; without it the first
     `/actuator/prometheus` scrape threw `MissingReflectionRegistrationError`, which Reactor treats as fatal, and hung.
2. `probe` (`busybox:1.37-musl`): a static busybox and a `wget` link for the health check.
3. runtime (`gcr.io/distroless/base-debian13:nonroot`): glibc (the executable links it dynamically; the musl variant of
   the GraalVM image exists for amd64 only), no shell or package manager, user `nonroot` (65532), the same
   `HEALTHCHECK` as the JVM image, `ENTRYPOINT /app/application -XX:MaximumHeapSizePercent=50 -Dsun.net.inetaddr.ttl=5`
   (`MaximumHeapSizePercent` is the native image's `MaxRAMPercentage`).

Costs and limits:

- **Build time and memory**: 6 to 11 minutes and a 7.5 GB builder peak per service on the development machine
  (8 CPUs), so one at a time (`COMPOSE_PARALLEL_LIMIT=1`): about an hour for all seven, against about 4 minutes for the
  JVM images. The executables are 200 to 280 MB (preserving `com.ecommerce.*` keeps more code than reachability alone).
- **Bean conditions are fixed at build time** (Spring AOT): `@ConditionalOnProperty` and `@Profile` beans are decided
  when the image is built, with the defaults (`platform.messaging.enabled`, `notification.delivery.enabled`, ... all
  true). Properties and profile-specific property files (`SEED=true` and `application-seed.yml`) still apply at run time.
- **Reflection outside `com.ecommerce`** needs an entry in `native/metadata/reachability-metadata.json`. A missing one
  shows up as `MissingReflectionRegistrationError` in the log, with the entry to add.
- **Measured and left out**: `-H:+CompactingOldGen` (compacting instead of copying the old generation) changed the
  peak anonymous memory of catalog by 1 MiB. Compact object headers (JEP 519) are a HotSpot feature; native-image has its
  own object layout and no such switch.
- **No JIT**: in theory lower peak throughput than C2 on long-running hot paths; in the measured profile the native
  stack matched the best JVM run (184 against 185 req/s, all thresholds met; "Memory measurements"), these services
  being I/O bound.

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
