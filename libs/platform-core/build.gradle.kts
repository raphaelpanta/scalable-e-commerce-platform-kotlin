// Shared plumbing with no business rules (constitution Principle VI): RFC 9457 problems, correlation id, shared value
// objects, resource-server security, observability defaults and the test fixtures every service reuses.
plugins {
    id("kotlin-library")
    id("pitest")
}

// Only the framework-free packages are mutation tested; Spring wiring is covered by the services' integration layers.
mutation { targetPackage.set("com.ecommerce.platform.core") }

dependencies {
    api(libs.arrow.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.context.propagation)
    implementation(libs.micrometer.registry.prometheus)

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
}
