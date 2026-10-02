pluginManagement {
    // The toolchain resolver is a settings plugin; settings scripts cannot use the `libs` accessor,
    // so its version is read from the catalogue instead of being written here (FR-004).
    val foojayResolver =
        settings.rootDir
            .resolve("gradle/libs.versions.toml")
            .readLines()
            .first { it.trimStart().startsWith("foojay-resolver =") }
            .substringAfter('"')
            .substringBefore('"')
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version foojayResolver
    }
}

plugins {
    // Provisions the JDK 25 toolchain (and the daemon JVM) when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "scalable-e-commerce-platform"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// Shared libraries, the gateway and the cross-service acceptance suite (feature 004).
include(":libs:platform-core", ":libs:platform-messaging", ":services:gateway", ":acceptance")

fun includeService(name: String) {
    include(":services:$name:domain", ":services:$name:application", ":services:$name:infrastructure")
}

// --- includeService registry (the newService task appends below this line) ---
includeService("catalog")
includeService("identity")
includeService("cart")
includeService("order")
includeService("payment")
includeService("notification")
