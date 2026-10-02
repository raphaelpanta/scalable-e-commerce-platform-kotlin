// Event envelope, transactional outbox relay and idempotent consumer support shared by every service (plan research §7).
plugins {
    id("kotlin-library")
}

dependencies {
    api(project(":libs:platform-core"))
    // Part of the public API: payloads are Jackson nodes, listeners receive Kafka records and acknowledgments,
    // the outbox and the idempotent consumer write through the service's R2DBC DatabaseClient.
    api(libs.spring.boot.starter.kafka)
    api(libs.spring.boot.starter.data.r2dbc)
    api(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.micrometer.core)
    implementation(libs.r2dbc.postgresql)

    // src/testFixtures: Kafka Testcontainer, recorded events, envelope builders and outbox assertions for services
    testFixturesApi(libs.spring.boot.starter.kafka.test)
    testFixturesApi(libs.spring.boot.testcontainers)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.awaitility)
    testFixturesImplementation(libs.spring.kafka)
    testFixturesImplementation(libs.spring.boot.starter.data.r2dbc)

    testImplementation(testFixtures(project(":libs:platform-core")))
    testImplementation(libs.spring.boot.starter.kafka.test)
    testImplementation(libs.awaitility)
    integrationTestImplementation(libs.spring.boot.starter.flyway)
    integrationTestImplementation(libs.flyway.database.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.reactor)
    integrationTestRuntimeOnly(libs.postgresql)
}
