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

- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -Dnetworkaddress.cache.ttl=5`: heap sized from the container memory limit and a
  5 s JVM DNS cache so that DNS-based discovery notices added and removed replicas quickly.
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
service as healthy.
