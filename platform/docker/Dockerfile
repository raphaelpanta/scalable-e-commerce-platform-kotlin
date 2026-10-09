# syntax=docker/dockerfile:1
# Shared multi-stage image recipe for every service; built by Docker Compose (platform/compose/docker-compose.yml) and
# by `./gradlew :services:<name>:infrastructure:dockerImage` from the repository root with
# --build-arg SERVICE_MODULE=<gradle path of the deployable module> (and, optionally,
# --build-arg APP_JAR=<pre-built boot jar inside the context>).
# Base images are pinned by digest (resolved 2026-10-02); refresh them deliberately (docs/build.md, Follow-ups).
#
# One shared build stage (T132, SC-006): `build` compiles the boot jar of EVERY deployable (the gateway and each
# services/*/infrastructure module) in a single Gradle invocation and does not depend on SERVICE_MODULE, so the seven
# image builds of `docker compose up --build` send the same context through the same instructions and the engine
# reuses the stage from its layer cache after the first image (BuildKit and buildah/Podman alike). Only `layers` and
# the runtime stage differ per SERVICE_MODULE. Never reference SERVICE_MODULE in `build`: that would split the cache
# per service again (platform/docker/README.md).
#
# Memory (feature 008, platform/docker/README.md "Runtime settings"): the JVM is tuned for small containers and every image
# carries a JDK 25 AOT cache recorded by a training run at build time, so a service needs less memory and starts faster
# than before at a higher throughput. For the smallest footprint build the GraalVM native image instead
# (platform/docker/Dockerfile.native).

FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc AS build
# Optional: path, relative to the build context, of an already built boot jar (CI: the artifact of the `jar` job of
# .github/workflows/service-ci.yml). When set, the stage copies it to /jars/prebuilt.jar and does not compile; empty
# (the default, as Compose and `dockerImage` use it) builds every deployable's jar here with Gradle.
ARG APP_JAR=""
WORKDIR /workspace
COPY . .
# Deployables: services/gateway and every services/<name>/infrastructure directory (a service added with
# `./gradlew newService` is picked up without editing this file). Jar names follow the module path:
# :services:catalog:infrastructure -> /jars/services-catalog-infrastructure.jar.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    set -e; \
    mkdir -p /jars; \
    if [ -n "${APP_JAR}" ]; then \
      test -f "${APP_JAR}"; \
      cp "${APP_JAR}" /jars/prebuilt.jar; \
    else \
      modules="services/gateway $(ls -d services/*/infrastructure)"; \
      tasks=""; \
      for dir in ${modules}; do tasks="${tasks} :$(echo "${dir}" | tr / :):bootJar"; done; \
      ./gradlew -q --no-daemon ${tasks}; \
      for dir in ${modules}; do \
        jar="$(find "${dir}/build/libs" -name '*.jar' ! -name '*-plain.jar')"; \
        test "$(echo "${jar}" | wc -l)" -eq 1 && test -f "${jar}"; \
        cp "${jar}" "/jars/$(echo "${dir}" | tr / -).jar"; \
      done; \
    fi

# Per service from here on: picks the jar of SERVICE_MODULE (or the pre-built one) out of the shared stage, whose file
# system it inherits (no copy of the seven jars), and extracts the Spring Boot layers as a plain `java -jar` layout
# (app.jar plus lib/*.jar): an AOT cache can only hold classes that come from jar files, not from the exploded
# BOOT-INF/classes directory of the JarLauncher layout. It also writes throw-away keys for the training run below (the
# JDK is only here): identity's Ed25519 signing key and the gateway's AES-256 browser session key. They are mounted into
# that run and never copied into an image.
FROM build AS layers
ARG SERVICE_MODULE
WORKDIR /layers
RUN set -e; \
    if [ -f /jars/prebuilt.jar ]; then \
      jar=/jars/prebuilt.jar; \
    else \
      test -n "${SERVICE_MODULE}" || { echo "build-arg SERVICE_MODULE is required" >&2; exit 1; }; \
      jar="/jars/$(echo "${SERVICE_MODULE}" | sed 's#^:##; s#:#-#g').jar"; \
      test -f "${jar}" || { echo "no boot jar for ${SERVICE_MODULE} (${jar})" >&2; exit 1; }; \
    fi; \
    java -Djarmode=tools -jar "${jar}" extract --layers --application-filename app.jar --destination extracted; \
    mkdir -p /layers/training; \
    printf '%s\n' 'void main(String[] args) throws Exception {' \
      '  var b64 = java.util.Base64.getEncoder();' \
      '  var signing = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();' \
      '  java.nio.file.Files.writeString(java.nio.file.Path.of(args[0], "signing-key"), b64.encodeToString(signing.getEncoded()));' \
      '  var session = new byte[32];' \
      '  new java.security.SecureRandom().nextBytes(session);' \
      '  java.nio.file.Files.writeString(java.nio.file.Path.of(args[0], "session-key"), b64.encodeToString(session));' \
      '}' > /tmp/TrainingKeys.java; \
    java /tmp/TrainingKeys.java /layers/training

# A statically linked busybox for the health check (the JRE image has no curl or wget); the native image uses the same.
FROM busybox:1.37-musl@sha256:5cec3fc171c87218698e85a52af7087de727372aae264a787b8112901a5b0092 AS probe
RUN mkdir -p /out && cp /bin/busybox /out/busybox && ln -s busybox /out/wget

# Runtime: the glibc (Ubuntu) JRE. The Alpine (musl) JRE was measured as well (2026-10-08, platform/docker/README.md
# "Memory measurements"): about the same memory, but musl's allocator and string routines cost the stack about 20 % of
# its throughput under the performance profile, so the image stays on glibc; a container runs no OS processes besides the
# JVM, so the larger base costs disk, not memory.
FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
ARG SERVICE_MODULE
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
COPY --from=probe /out/ /usr/local/bin/
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
# Memory (measured 2026-10-08 with one service per container, 1 CPU, 768 MiB, under load, and for the whole stack under
# the performance profile; platform/docker/README.md "Runtime settings"). Heap committed was only 70 to 100 MiB of the
# 307 MiB maximum; the rest of the footprint was metaspace (about 85 MiB, mostly removed by the AOT cache below), JIT-
# compiled code and native allocations. Hence:
#   -XX:+UseSerialGC               explicit: the JVM picks G1 as soon as a pod gets 2 CPUs, which costs remembered sets
#   -XX:+UseCompactObjectHeaders   8-byte object headers (JDK 25, JEP 519): 10 to 20 % less heap for the same objects
#   -XX:MaxRAMPercentage=50        heap ceiling relative to the container limit: 256 MiB of the 512m Compose default. With
#                                  the AOT cache the anonymous off-heap memory is about 130 to 180 MiB (it was 300 to
#                                  400 MB, which made 50 % of 768 MiB fail before); at 40 % of 512m the browse path
#                                  collected too often and lost 10 % of its throughput
#   -Xss1m                         thread stacks of 1 MiB instead of the 2 MiB aarch64 default
#   -XX:MaxDirectMemorySize=128m   Netty's direct buffers default to the heap size; capped as before
#   -Djdk.nio.maxCachedBufferSize  bounds the per-thread temporary direct buffers of blocking NIO (JDBC, Kafka client)
#   -XX:TrimNativeHeapInterval     returns freed glibc malloc memory to the OS every 30 s (JDK 22+), with
#   + MALLOC_ARENA_MAX=2           at most two malloc arenas instead of eight per CPU of the machine
#   -Dsun.net.inetaddr.ttl=5       keeps JVM DNS lookups short-lived so that platform DNS discovery (Compose service
#                                  names, later Kubernetes Services) drops removed replicas within 5 seconds (research.md
#                                  section 2, FR-024, SC-008). The former -Dnetworkaddress.cache.ttl=5 is a security
#                                  property, not a system property, and left the JVM default of 30 s in place
# C2 stays on: C1 only (-XX:TieredStopAtLevel=1) saves another 60 to 90 MiB per service but cost the stack 30 % of its
# throughput; set it per container with JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1 where memory matters more than speed.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=50 -Xss1m -XX:MaxDirectMemorySize=128m -Djdk.nio.maxCachedBufferSize=262144 -XX:TrimNativeHeapInterval=30000 -Dsun.net.inetaddr.ttl=5" \
    MALLOC_ARENA_MAX=2
# AOT cache (JDK 25, JEP 483/514/515): a training run starts the application until its context is refreshed
# (spring.context.exit=onRefresh) and records the loaded and linked classes and method profiles in app.aot. At run time
# the JVM maps that file instead of parsing and linking the classes again: metaspace drops from about 80 to 30 MiB, the
# class data is shared, read-only, file-backed memory, and the service starts in about half the time. The training run
# needs no database, broker or collector: Flyway, the Kafka listeners and the topic admin are switched off for it, and the
# variables a service refuses to start without get throw-away values (database user and password, the internal token,
# identity's signing key and the gateway's session key from the `layers` stage). The cache must be written by this JRE with these JAVA_TOOL_OPTIONS;
# a failed run fails the build rather than ship an image whose JVM would log AOT errors at every start.
RUN --mount=type=bind,from=layers,source=/layers/training,target=/training \
    set -e; \
    ctx="$(echo "${SERVICE_MODULE}" | cut -d: -f3 | tr '[:lower:]' '[:upper:]')"; \
    if ! env "${ctx}_DB_USER=training" "${ctx}_DB_PASSWORD=training" INTERNAL_API_TOKEN=training SEED=false \
        IDENTITY_SIGNING_KEY="$(cat /training/signing-key)" BROWSER_SESSION_KEY="$(cat /training/session-key)" \
        SPRING_FLYWAY_ENABLED=false \
        SPRING_KAFKA_ADMIN_AUTO_CREATE=false SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
        java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar app.jar > /tmp/training.log 2>&1 \
        || ! test -s app.aot; then \
      tail -n 60 /tmp/training.log >&2; echo "AOT cache training run failed for ${SERVICE_MODULE}" >&2; exit 1; \
    fi; \
    rm -f /tmp/training.log
EXPOSE 8080 8081
# Every service and the gateway expose the readiness health group on the management port 8081
# (docs/service-conventions.md section 2): the container is healthy once the application accepts traffic (and, where the
# group includes it, its database answers). busybox wget (the probe stage) exits non-zero on the 503 the group answers
# while not ready.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["wget", "-qO", "/dev/null", "http://127.0.0.1:8081/actuator/health/readiness"]
USER app
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
