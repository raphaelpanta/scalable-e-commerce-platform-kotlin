// Convention for a service's `infrastructure` module: the kotlin-boot-app application (WebFlux, test layers,
// `dockerImage`) plus its sibling domain and application modules, R2DBC and Flyway on PostgreSQL, Testcontainers,
// and the shared Konsist architecture rules in the unit layer (Principles II, IV, V).
plugins {
    id("kotlin-boot-app")
}

val catalog = the<VersionCatalogsExtension>().named("libs")
val service: Project = requireNotNull(project.parent) { "$path must live inside a service directory" }

fun library(alias: String): Provider<MinimalExternalModuleDependency> = catalog.findLibrary(alias).get()

dependencies {
    "implementation"(project("${service.path}:domain"))
    "implementation"(project("${service.path}:application"))
    listOf(
        "spring-boot-starter-data-r2dbc",
        "spring-boot-starter-flyway",
        "flyway-database-postgresql",
        "r2dbc-postgresql",
    ).forEach { "implementation"(library(it)) }
    "runtimeOnly"(library("postgresql"))

    // testImplementation is inherited by every layer (TestLayers.kt)
    listOf(
        "spring-boot-testcontainers",
        "testcontainers-postgresql",
        "testcontainers-junit-jupiter",
        "testcontainers-r2dbc",
    ).forEach { "testImplementation"(library(it)) }
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
