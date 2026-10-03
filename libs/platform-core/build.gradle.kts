// Shared plumbing with no business rules (constitution Principle VI): RFC 9457 problems, correlation id, shared value
// objects, resource-server security, observability defaults and the test fixtures every service reuses.
plugins {
    id("kotlin-library")
    id("pitest")
}

// Only the framework-free packages are mutation tested; Spring wiring is covered by the services' integration layers.
mutation { targetPackage.set("com.ecommerce.platform.core") }

// The property specs of the core sit next to the mutated classes; the WebTestClient tests of the Spring layer kill
// nothing there and would only slow every mutant down.
pitest { targetTests.set(setOf("com.ecommerce.platform.core.*")) }

dependencies {
    api(libs.arrow.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.spring.boot.starter.webflux)
    // Boot's observation-enabled WebClient.Builder (Boot 4 moved it out of WebFlux): every service that builds an
    // internal client with WebClientDefaults gets the auto-configured builder, so outbound calls carry `traceparent`.
    api(libs.spring.boot.starter.webclient)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    // Logback events as OpenTelemetry log records for Loki (logback-spring.xml, OpenTelemetryLogbackAutoConfiguration)
    implementation(libs.opentelemetry.logback.appender)
    implementation(libs.jackson.module.kotlin)
    // Public through com.ecommerce.platform.observability.ReactorThreadLocals (a ThreadContextElement of its Scope)
    api(libs.context.propagation)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.awaitility)

    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.testcontainers.junit.jupiter)
    testFixturesApi(libs.testcontainers.r2dbc)
    testFixturesApi(libs.spring.boot.testcontainers)
    testFixturesApi(libs.wiremock)
    testFixturesApi(libs.konsist)
    testFixturesApi(libs.kotest.assertions.core)
    testFixturesApi(libs.kotest.property)
    testFixturesImplementation(libs.spring.boot.starter.webflux.test)
    testFixturesImplementation(libs.spring.security.oauth2.jose)
    testFixturesImplementation(libs.jackson.module.kotlin)

    // The fixtures smoke test talks JDBC to the PostgreSQL container.
    integrationTestRuntimeOnly(libs.postgresql)
}
