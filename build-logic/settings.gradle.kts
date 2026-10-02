pluginManagement {
    // Settings scripts cannot use the `libs` accessor; the version comes from the catalogue (FR-004).
    val foojayResolver =
        settings.rootDir
            .resolve("../gradle/libs.versions.toml")
            .readLines()
            .first { it.trimStart().startsWith("foojay-resolver =") }
            .substringAfter('"')
            .substringBefore('"')
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version foojayResolver
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
