import com.ecommerce.build.registerTestLayer
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Convention for a service's `infrastructure` module: the Spring Boot WebFlux application with its adapters,
// the four test layers (test, integrationTest, contractTest via `pact`, acceptanceTest), the shared Konsist
// architecture rules in the unit layer, and the `dockerImage` task (Principles II, IV, V).
plugins {
    id("kotlin-base")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
    id("pact")
    id("docker-image")
}

val catalog = the<VersionCatalogsExtension>().named("libs")
val service: Project = requireNotNull(project.parent) { "$path must live inside a service directory" }

fun library(alias: String): Provider<MinimalExternalModuleDependency> = catalog.findLibrary(alias).get()

registerTestLayer("integrationTest")
registerTestLayer("acceptanceTest")

dependencies {
    "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    "implementation"(project("${service.path}:domain"))
    "implementation"(project("${service.path}:application"))
    listOf(
        "spring-boot-starter-webflux",
        "spring-boot-starter-actuator",
        "spring-boot-starter-data-r2dbc",
        "spring-boot-starter-flyway",
        "flyway-database-postgresql",
        "kotlinx-coroutines-reactor",
        "jackson-module-kotlin",
        "micrometer-registry-prometheus",
        "context-propagation",
        "r2dbc-postgresql",
    ).forEach { "implementation"(library(it)) }
    "runtimeOnly"(library("postgresql"))

    // testImplementation is inherited by every layer (TestLayers.kt)
    listOf(
        "spring-boot-starter-webflux-test",
        "spring-boot-testcontainers",
        "testcontainers-postgresql",
        "testcontainers-junit-jupiter",
        "testcontainers-r2dbc",
        "wiremock",
        "mockk",
        "konsist",
    ).forEach { "testImplementation"(library(it)) }
    listOf("cucumber-java", "cucumber-spring", "cucumber-junit-platform-engine", "junit-platform-suite")
        .forEach { "acceptanceTestImplementation"(library(it)) }
}

// Architecture rules are written once in config/architecture and run inside every infrastructure module.
val architectureSources: Directory = isolated.rootProject.projectDirectory.dir("config/architecture/src/test/kotlin")
if (architectureSources.asFile.isDirectory) {
    kotlin.sourceSets.named("test") { kotlin.srcDir(architectureSources) }
}

tasks.named<Test>("test") {
    systemProperty("architecture.serviceRoot", service.projectDir.absolutePath)
    systemProperty("architecture.basePackage", "com.ecommerce.${service.name}")
}

afterEvaluate {
    if (name != "infrastructure") {
        throw GradleException("service module $path must be named infrastructure")
    }
}

springBoot {
    buildInfo()
}
