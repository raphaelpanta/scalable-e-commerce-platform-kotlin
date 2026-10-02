import com.ecommerce.build.registerTestLayer
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Convention for shared libraries (:libs:platform-core, :libs:platform-messaging) and the cross-service suite
// (:acceptance): Kotlin on the Spring Boot BOM, so Spring, Jackson and Kafka classes need no version; test
// fixtures (`src/testFixtures/kotlin`, consumed with testFixtures(project(...))); the unit and integrationTest
// layers with Kotest, MockK, WebTestClient, Testcontainers and WireMock. No Spring Boot application, no image.
// Mutation testing is opt-in: a library applies `pitest` and sets mutation { targetPackage.set(...) }.
plugins {
    id("kotlin-base")
    id("org.jetbrains.kotlin.plugin.spring")
    `java-test-fixtures`
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun library(alias: String): Provider<MinimalExternalModuleDependency> = catalog.findLibrary(alias).get()

registerTestLayer("integrationTest")

dependencies {
    "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    "testFixturesImplementation"(platform(SpringBootPlugin.BOM_COORDINATES))

    // testImplementation is inherited by every layer (TestLayers.kt)
    listOf(
        "mockk",
        "spring-boot-starter-webflux-test",
        "spring-boot-testcontainers",
        "testcontainers-postgresql",
        "testcontainers-junit-jupiter",
        "testcontainers-r2dbc",
        "wiremock",
    ).forEach { "testImplementation"(library(it)) }
}
