// Event envelope, transactional outbox relay and idempotent consumer support shared by every service (plan research §7).
plugins {
    id("kotlin-library")
}

dependencies {
    api(project(":libs:platform-core"))
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.r2dbc.postgresql)

    testImplementation(testFixtures(project(":libs:platform-core")))
    testImplementation(libs.spring.boot.starter.kafka.test)
    testImplementation(libs.awaitility)
    integrationTestImplementation(libs.spring.boot.starter.flyway)
    integrationTestImplementation(libs.flyway.database.postgresql)
    integrationTestRuntimeOnly(libs.postgresql)
}
